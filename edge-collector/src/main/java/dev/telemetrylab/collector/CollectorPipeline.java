package dev.telemetrylab.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.telemetrylab.contracts.CollectorHeartbeat;
import dev.telemetrylab.contracts.ControllerReadingsPage;
import dev.telemetrylab.contracts.CursorGone;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

public class CollectorPipeline {
  private static final Logger LOGGER = LoggerFactory.getLogger(CollectorPipeline.class);

  private final CollectorRepository repository;
  private final RestClient sourceClient;
  private final RestClient gatewayClient;
  private final CollectorProperties properties;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final RetryPolicy retryPolicy;
  private final MeterRegistry meterRegistry;
  private final SpoolWatermarks watermarks;
  private final AtomicBoolean sourceConnected = new AtomicBoolean();
  private final AtomicReference<Instant> lastSuccessfulUpload = new AtomicReference<>();
  private final AtomicLong gapCount = new AtomicLong();
  private final Counter observationsReceived;
  private final Counter uploadRetry;

  CollectorPipeline(
      CollectorRepository repository,
      RestClient sourceClient,
      RestClient gatewayClient,
      CollectorProperties properties,
      ObjectMapper objectMapper,
      Clock clock,
      MeterRegistry meterRegistry) {
    this.repository = repository;
    this.sourceClient = sourceClient;
    this.gatewayClient = gatewayClient;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.meterRegistry = meterRegistry;
    this.watermarks =
        new SpoolWatermarks(
            properties.spoolMaxRows(),
            properties.highWatermarkPercent(),
            properties.lowWatermarkPercent());
    this.retryPolicy =
        new RetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(2), ThreadLocalRandom.current());
    this.observationsReceived = meterRegistry.counter("edge_source_observations_received_total");
    this.uploadRetry = meterRegistry.counter("edge_upload_retry_total");
    registerGauges(meterRegistry);
  }

  @PostConstruct
  void recover() {
    repository.recoverInterruptedUploads();
  }

  @Scheduled(fixedDelayString = "${telemetry.collector.poll-delay-ms:1000}")
  @WithSpan("collector.source.poll")
  public void pollSource() {
    CollectorState state = repository.state();
    if (state.gapDetected()) {
      return;
    }
    long count = repository.unacknowledgedCount();
    if (state.pollingPaused() && watermarks.remainPaused(count)) {
      return;
    }
    if (state.pollingPaused()) {
      repository.setPollingPaused(false);
      LOGGER.info("Collector polling resumed below the low-water mark");
    }
    if (watermarks.shouldPause(count, properties.pollLimit())) {
      repository.setPollingPaused(true);
      LOGGER.warn("Collector polling paused at the spool high-water mark; spoolRows={}", count);
      return;
    }

    try {
      ControllerReadingsPage page =
          sourceClient
              .get()
              .uri(
                  builder -> {
                    builder
                        .path("/controller/v1/readings")
                        .queryParam("afterSequence", state.sourceCursor())
                        .queryParam("limit", properties.pollLimit());
                    if (state.sourceEpoch() != null) {
                      builder.queryParam("sourceEpoch", state.sourceEpoch());
                    }
                    return builder.build();
                  })
              .retrieve()
              .body(ControllerReadingsPage.class);
      if (page != null) {
        int persisted = repository.persistPage(page);
        observationsReceived.increment(persisted);
      }
      sourceConnected.set(true);
      meterRegistry.counter("edge_source_poll_total", "result", "success").increment();
    } catch (HttpClientErrorException.Gone exception) {
      handleCursorGone(exception);
    } catch (RuntimeException exception) {
      sourceConnected.set(false);
      meterRegistry.counter("edge_source_poll_total", "result", "failure").increment();
      LOGGER.warn("Source poll failed; errorType={}", exception.getClass().getSimpleName());
    }
  }

  @Scheduled(fixedDelayString = "${telemetry.collector.batch-delay-ms:500}")
  @WithSpan("collector.sqlite.create_batch")
  public void createOutboundBatch() {
    try {
      repository
          .createBatchIfReady()
          .ifPresent(
              batch ->
                  LOGGER.info(
                      "Persisted immutable outbound batch; batchId={} observationCount={}"
                          + " checksum={}",
                      batch.batchId(),
                      batch.observationCount(),
                      batch.checksum()));
    } catch (RuntimeException exception) {
      LOGGER.error("Outbound batch creation failed", exception);
    }
  }

  @Scheduled(fixedDelayString = "${telemetry.collector.upload-delay-ms:300}")
  @WithSpan("collector.batch.upload")
  public void uploadNextBatch() {
    Optional<OutboundBatch> candidate = repository.claimNextUpload();
    if (candidate.isEmpty()) {
      return;
    }
    OutboundBatch batch = candidate.get();
    try {
      gatewayClient
          .post()
          .uri("/api/v1/ingestion/batches")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.localToken())
          .header(HttpHeaders.CONTENT_ENCODING, "gzip")
          .header("Content-Digest", batch.contentDigest())
          .contentType(MediaType.APPLICATION_JSON)
          .body(batch.compressedPayload())
          .retrieve()
          .toBodilessEntity();
      repository.acknowledge(batch.batchId());
      Instant now = clock.instant();
      lastSuccessfulUpload.set(now);
      meterRegistry.counter("edge_upload_total", "result", "success").increment();
      meterRegistry.counter("edge_uploaded_observations_total").increment(batch.observationCount());
      LOGGER.info(
          "Gateway acknowledged durable batch; batchId={} attempt={}",
          batch.batchId(),
          batch.attemptCount());
    } catch (RuntimeException exception) {
      Duration delay = retryPolicy.nextDelay(batch.attemptCount());
      repository.fail(batch.batchId(), clock.instant().plus(delay), safeError(exception));
      uploadRetry.increment();
      meterRegistry.counter("edge_upload_total", "result", "failure").increment();
      LOGGER.warn(
          "Batch upload failed and remains durable; batchId={} attempt={} retryDelayMs={}"
              + " errorType={}",
          batch.batchId(),
          batch.attemptCount(),
          delay.toMillis(),
          exception.getClass().getSimpleName());
    }
  }

  @Scheduled(
      fixedDelayString = "${telemetry.collector.heartbeat-delay-ms:30000}",
      initialDelayString = "${telemetry.collector.heartbeat-initial-delay-ms:3000}")
  public void heartbeat() {
    CollectorHeartbeat heartbeat =
        new CollectorHeartbeat(
            properties.collectorVersion(),
            properties.configurationVersion(),
            properties.sourceAdapterVersion(),
            repository.unacknowledgedCount(),
            repository.oldestUnsentAgeSeconds(),
            lastSuccessfulUpload.get(),
            sourceConnected.get());
    try {
      gatewayClient
          .post()
          .uri("/api/v1/collectors/{collectorId}/heartbeat", properties.collectorId())
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.localToken())
          .contentType(MediaType.APPLICATION_JSON)
          .body(heartbeat)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException exception) {
      LOGGER.warn("Collector heartbeat rejected; status={}", exception.getStatusCode().value());
    } catch (RuntimeException exception) {
      LOGGER.warn("Collector heartbeat failed; errorType={}", exception.getClass().getSimpleName());
    }
  }

  @Scheduled(fixedDelayString = "${telemetry.collector.prune-delay-ms:60000}")
  public void pruneAcknowledged() {
    repository.pruneAcknowledged();
  }

  public Map<String, Object> status() {
    CollectorState state = repository.state();
    return Map.ofEntries(
        Map.entry("collectorId", properties.collectorId()),
        Map.entry("sourceEpoch", state.sourceEpoch() == null ? "" : state.sourceEpoch()),
        Map.entry("sourceCursor", state.sourceCursor()),
        Map.entry("sourceConnected", sourceConnected.get()),
        Map.entry("gapDetected", state.gapDetected()),
        Map.entry("gapDetail", state.gapDetail() == null ? "" : state.gapDetail()),
        Map.entry("pollingPaused", state.pollingPaused()),
        Map.entry("spoolObservationCount", repository.unacknowledgedCount()),
        Map.entry("pendingBatchCount", repository.pendingBatchCount()),
        Map.entry("oldestUnsentObservationAgeSeconds", repository.oldestUnsentAgeSeconds()),
        Map.entry(
            "lastSuccessfulUploadAt",
            lastSuccessfulUpload.get() == null ? "" : lastSuccessfulUpload.get().toString()));
  }

  public void recoverGap(String sourceEpoch, long cursor) {
    repository.recoverGap(sourceEpoch, cursor);
    gapCount.set(0);
  }

  private void handleCursorGone(HttpClientErrorException.Gone exception) {
    try {
      CursorGone detail =
          objectMapper.readValue(exception.getResponseBodyAsByteArray(), CursorGone.class);
      String serialized = objectMapper.writeValueAsString(detail);
      repository.markGap(serialized);
      gapCount.incrementAndGet();
      sourceConnected.set(true);
      meterRegistry.counter("edge_source_poll_total", "result", "cursor_expired").increment();
      meterRegistry.counter("edge_source_data_gap_total").increment();
      LOGGER.error(
          "Source cursor expired; explicit recovery is required; sourceEpoch={} earliestSequence={}"
              + " latestSequence={}",
          detail.sourceEpoch(),
          detail.earliestSequence(),
          detail.latestSequence());
    } catch (Exception parseFailure) {
      repository.markGap("Cursor expired, but response details could not be parsed");
      LOGGER.error("Source cursor expired and response parsing failed", parseFailure);
    }
  }

  private void registerGauges(MeterRegistry registry) {
    Gauge.builder("edge_spool_observations", repository, CollectorPipeline::safeSpoolCount)
        .register(registry);
    Gauge.builder(
            "edge_spool_utilization_ratio",
            repository,
            ignored -> safeSpoolCount(repository) / (double) properties.spoolMaxRows())
        .register(registry);
    Gauge.builder(
            "edge_oldest_unsent_age_seconds", repository, CollectorPipeline::safeOldestUnsentAge)
        .register(registry);
    Gauge.builder(
            "edge_last_successful_upload_timestamp",
            lastSuccessfulUpload,
            reference -> reference.get() == null ? 0 : reference.get().getEpochSecond())
        .register(registry);
    Gauge.builder("edge_source_data_gap", gapCount, value -> value.get() > 0 ? 1 : 0)
        .register(registry);
  }

  private static double safeSpoolCount(CollectorRepository repository) {
    try {
      return repository.unacknowledgedCount();
    } catch (RuntimeException exception) {
      return Double.NaN;
    }
  }

  private static double safeOldestUnsentAge(CollectorRepository repository) {
    try {
      return repository.oldestUnsentAgeSeconds();
    } catch (RuntimeException exception) {
      return Double.NaN;
    }
  }

  private static String safeError(RuntimeException exception) {
    String message = exception.getMessage();
    return exception.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}
