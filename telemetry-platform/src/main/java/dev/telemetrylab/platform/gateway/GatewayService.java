package dev.telemetrylab.platform.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.telemetrylab.contracts.BatchReference;
import dev.telemetrylab.contracts.RawObservation;
import dev.telemetrylab.platform.PlatformProblemException;
import dev.telemetrylab.platform.PlatformProperties;
import dev.telemetrylab.platform.store.RawObjectStore;
import dev.telemetrylab.platform.store.S3RawObjectStore.RawObjectConflictException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(
    name = "telemetry.platform.mode",
    havingValue = "gateway",
    matchIfMissing = true)
class GatewayService {
  private static final Logger LOGGER = LoggerFactory.getLogger(GatewayService.class);
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter HOUR =
      DateTimeFormatter.ofPattern("HH").withZone(ZoneOffset.UTC);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final RawObjectStore rawObjectStore;
  private final ObjectMapper objectMapper;
  private final PlatformProperties properties;
  private final Clock clock;
  private final MeterRegistry meterRegistry;

  GatewayService(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      RawObjectStore rawObjectStore,
      ObjectMapper objectMapper,
      PlatformProperties properties,
      Clock clock,
      MeterRegistry meterRegistry) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.rawObjectStore = rawObjectStore;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.clock = clock;
    this.meterRegistry = meterRegistry;
  }

  IngestionResult accept(ValidatedBatch batch, boolean failAfterRawWrite) {
    Timer.Sample timer = Timer.start(meterRegistry);
    try {
      IngestionResult result =
          transactions.execute(
              status -> {
                jdbc.query(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                    ignored -> {},
                    batch.contract().batchId());
                Optional<ExistingBatch> existing = existing(batch.contract().batchId());
                if (existing.isPresent()) {
                  return handleExisting(batch, existing.get());
                }

                String objectKey = findOrCreateObject(batch);
                if (failAfterRawWrite && properties.failureInjectionEnabled()) {
                  throw new InjectedFailureException();
                }
                insertManifestAndOutbox(batch, objectKey);
                return new IngestionResult(
                    batch.contract().batchId(), objectKey, false, "ACCEPTED");
              });
      meterRegistry
          .counter(
              "ingestion_batches_total", "result", result.duplicate() ? "duplicate" : "accepted")
          .increment();
      if (result.duplicate()) {
        meterRegistry.counter("ingestion_duplicate_batches_total").increment();
      } else {
        meterRegistry
            .counter("ingestion_observations_received_total")
            .increment(batch.contract().observations().size());
      }
      return result;
    } catch (RawObjectConflictException exception) {
      meterRegistry.counter("ingestion_batches_total", "result", "conflict").increment();
      throw new PlatformProblemException(
          HttpStatus.CONFLICT,
          "BATCH_CHECKSUM_CONFLICT",
          "The batch ID is already associated with different bytes");
    } finally {
      timer.stop(meterRegistry.timer("ingestion_request_duration_seconds"));
    }
  }

  private IngestionResult handleExisting(ValidatedBatch batch, ExistingBatch existing) {
    if (!existing.checksum().equals(batch.checksum())) {
      throw new PlatformProblemException(
          HttpStatus.CONFLICT,
          "BATCH_CHECKSUM_CONFLICT",
          "The batch ID is already associated with a different checksum");
    }
    rawObjectStore.putIfAbsent(
        existing.objectKey(), batch.compressedBytes(), batch.checksum(), batch.contentDigest());
    return new IngestionResult(
        batch.contract().batchId(), existing.objectKey(), true, "ALREADY_ACCEPTED");
  }

  private String findOrCreateObject(ValidatedBatch batch) {
    Timer.Sample timer = Timer.start(meterRegistry);
    try {
      Optional<RawObjectStore.StoredObjectMetadata> orphan =
          rawObjectStore.findByBatchId(batch.contract().batchId());
      if (orphan.isPresent()) {
        if (!batch.checksum().equals(orphan.get().checksum())) {
          throw new RawObjectConflictException(
              orphan.get().objectKey(), batch.checksum(), orphan.get().checksum());
        }
        return orphan.get().objectKey();
      }
      String key = objectKey(batch);
      rawObjectStore.putIfAbsent(
          key, batch.compressedBytes(), batch.checksum(), batch.contentDigest());
      return key;
    } finally {
      timer.stop(meterRegistry.timer("ingestion_raw_store_duration_seconds"));
    }
  }

  private void insertManifestAndOutbox(ValidatedBatch batch, String objectKey) {
    List<Instant> timestamps =
        batch.contract().observations().stream().map(RawObservation::observedAt).sorted().toList();
    jdbc.update(
        """
        INSERT INTO ingestion_batch (
          batch_id, collector_id, collector_version, facility_id, contract_version,
          checksum, content_digest, object_key, received_at, minimum_observed_at,
          maximum_observed_at, observation_count, processing_status
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED')
        """,
        UUID.fromString(batch.contract().batchId()),
        batch.contract().collectorId(),
        batch.contract().collectorVersion(),
        batch.contract().facilityId(),
        batch.contract().contractVersion(),
        batch.checksum(),
        batch.contentDigest(),
        objectKey,
        java.sql.Timestamp.from(batch.receivedAt()),
        java.sql.Timestamp.from(timestamps.getFirst()),
        java.sql.Timestamp.from(timestamps.getLast()),
        batch.contract().observations().size());

    BatchReference reference =
        BatchReference.live(batch.contract().batchId(), objectKey, batch.checksum());
    String payload;
    try {
      payload = objectMapper.writeValueAsString(reference);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize outbox reference", exception);
    }
    Instant now = clock.instant();
    jdbc.update(
        """
        INSERT INTO outbox_event (
          event_id, event_type, batch_id, payload, trace_parent, created_at, next_attempt_at
        ) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)
        """,
        UUID.randomUUID(),
        "RawBatchStored",
        UUID.fromString(batch.contract().batchId()),
        payload,
        batch.traceParent(),
        java.sql.Timestamp.from(now),
        java.sql.Timestamp.from(now));
    LOGGER.info(
        "Durable ingestion acknowledgment boundary reached; batchId={} objectKey={} checksum={}",
        batch.contract().batchId(),
        objectKey,
        batch.checksum());
  }

  private Optional<ExistingBatch> existing(String batchId) {
    List<ExistingBatch> rows =
        jdbc.query(
            "SELECT checksum, object_key FROM ingestion_batch WHERE batch_id=?",
            (result, row) ->
                new ExistingBatch(result.getString("checksum"), result.getString("object_key")),
            UUID.fromString(batchId));
    return rows.stream().findFirst();
  }

  private static String objectKey(ValidatedBatch batch) {
    Instant received = batch.receivedAt();
    return "raw-observations/facility="
        + batch.contract().facilityId()
        + "/date="
        + DATE.format(received)
        + "/hour="
        + HOUR.format(received)
        + "/collector="
        + batch.contract().collectorId()
        + "/batch="
        + batch.contract().batchId()
        + ".json.gz";
  }

  record IngestionResult(String batchId, String objectKey, boolean duplicate, String status) {}

  private record ExistingBatch(String checksum, String objectKey) {}

  static final class InjectedFailureException extends RuntimeException {
    InjectedFailureException() {
      super("Injected crash-window failure after raw object persistence");
    }
  }
}
