package dev.telemetrylab.platform.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.telemetrylab.contracts.BatchReference;
import dev.telemetrylab.contracts.ContentDigest;
import dev.telemetrylab.contracts.ContractVersions;
import dev.telemetrylab.contracts.DeterministicIds;
import dev.telemetrylab.contracts.GzipCodec;
import dev.telemetrylab.contracts.ProcessingOutcome;
import dev.telemetrylab.contracts.RawObservation;
import dev.telemetrylab.contracts.RawObservationBatch;
import dev.telemetrylab.contracts.ReasonCode;
import dev.telemetrylab.platform.PlatformProperties;
import dev.telemetrylab.platform.store.RawObjectStore;
import dev.telemetrylab.platform.worker.ObservationEvaluator.ObservationDecision;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProcessingService {
  private static final Logger LOGGER = LoggerFactory.getLogger(ProcessingService.class);

  private final RawObjectStore rawObjectStore;
  private final ObjectMapper objectMapper;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObservationEvaluator evaluator;
  private final PlatformProperties properties;
  private final Clock clock;
  private final MeterRegistry meterRegistry;
  private final DistributionSummary endToEndLag;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "These mutable collaborators are application-scoped Spring infrastructure beans")
  public ProcessingService(
      RawObjectStore rawObjectStore,
      ObjectMapper objectMapper,
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      ObservationEvaluator evaluator,
      PlatformProperties properties,
      Clock clock,
      MeterRegistry meterRegistry) {
    this.rawObjectStore = rawObjectStore;
    this.objectMapper = objectMapper;
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.evaluator = evaluator;
    this.properties = properties;
    this.clock = clock;
    this.meterRegistry = meterRegistry;
    this.endToEndLag =
        DistributionSummary.builder("processing_end_to_end_lag_seconds")
            .serviceLevelObjectives(1, 5, 10, 20, 30, 60, 120, 300)
            .register(meterRegistry);
  }

  public ProcessingCounts process(BatchReference reference) {
    Timer.Sample timer = Timer.start(meterRegistry);
    try {
      RawObjectStore.RawObject rawObject = rawObjectStore.get(reference.objectKey());
      verifyRawObject(reference, rawObject);
      RawObservationBatch batch = decode(rawObject.content());
      verifyWorkerEnvelope(reference, batch);
      String mappingVersion =
          reference.mappingVersion() == null
              ? properties.defaultMappingVersion()
              : reference.mappingVersion();
      String qualityRulesVersion =
          reference.qualityRulesVersion() == null
              ? properties.defaultQualityRulesVersion()
              : reference.qualityRulesVersion();
      Instant replayFrom = parseOptionalInstant(reference.replayFrom());
      Instant replayTo = parseOptionalInstant(reference.replayTo());
      ProcessingCounts counts =
          transactions.execute(
              status ->
                  persist(
                      reference, batch, mappingVersion, qualityRulesVersion, replayFrom, replayTo));
      meterRegistry.counter("processing_batches_total", "result", "success").increment();
      return Objects.requireNonNull(counts);
    } catch (RuntimeException exception) {
      meterRegistry.counter("processing_batches_total", "result", "failure").increment();
      throw exception;
    } finally {
      timer.stop(meterRegistry.timer("processing_duration_seconds"));
    }
  }

  public void recordFailure(BatchReference reference, int deliveryAttempt, RuntimeException error) {
    if (reference == null || reference.batchId() == null) {
      return;
    }
    try {
      UUID batchId = UUID.fromString(reference.batchId());
      UUID replayId = reference.replayId() == null ? null : UUID.fromString(reference.replayId());
      Instant now = clock.instant();
      jdbc.update(
          """
          INSERT INTO processing_attempt (
            processing_attempt_id, batch_id, replay_id, mapping_version, quality_rules_version,
            attempt_number, status, started_at, completed_at, last_error
          ) VALUES (?, ?, ?, ?, ?, ?, 'FAILED', ?, ?, ?)
          """,
          UUID.randomUUID(),
          batchId,
          replayId,
          defaulted(reference.mappingVersion(), properties.defaultMappingVersion()),
          defaulted(reference.qualityRulesVersion(), properties.defaultQualityRulesVersion()),
          deliveryAttempt,
          Timestamp.from(now),
          Timestamp.from(now),
          truncate(error.toString()));
      if (replayId == null) {
        jdbc.update(
            "UPDATE ingestion_batch SET processing_attempt_count=processing_attempt_count+1,"
                + " last_error=? WHERE batch_id=?",
            truncate(error.toString()),
            batchId);
      } else {
        jdbc.update(
            "UPDATE replay_batch SET status='RETRYING', last_error=? WHERE replay_id=? AND"
                + " batch_id=?",
            truncate(error.toString()),
            replayId,
            batchId);
      }
    } catch (RuntimeException auditFailure) {
      LOGGER.warn(
          "Unable to record failed processing attempt; errorType={}",
          auditFailure.getClass().getSimpleName());
    }
  }

  private ProcessingCounts persist(
      BatchReference reference,
      RawObservationBatch batch,
      String mappingVersion,
      String qualityRulesVersion,
      Instant replayFrom,
      Instant replayTo) {
    UUID batchId = UUID.fromString(batch.batchId());
    UUID replayId = reference.replayId() == null ? null : UUID.fromString(reference.replayId());
    Manifest manifest = lockManifest(batchId);
    int attemptNumber = manifest.processingAttemptCount() + 1;
    UUID attemptId = UUID.randomUUID();
    Instant started = clock.instant();
    jdbc.update(
        """
        INSERT INTO processing_attempt (
          processing_attempt_id, batch_id, replay_id, mapping_version, quality_rules_version,
          attempt_number, status, started_at
        ) VALUES (?, ?, ?, ?, ?, ?, 'RUNNING', ?)
        """,
        attemptId,
        batchId,
        replayId,
        mappingVersion,
        qualityRulesVersion,
        attemptNumber,
        Timestamp.from(started));

    ProcessingCounts counts = new ProcessingCounts();
    for (RawObservation observation : batch.observations()) {
      if (replayId != null && !insideReplay(observation.observedAt(), replayFrom, replayTo)) {
        continue;
      }
      String observationId = stableId(batch.facilityId(), observation);
      ObservationDecision decision =
          prevalidate(
              observation, mappingVersion, qualityRulesVersion, manifest.receivedAt(), started);
      if (!decision.accepted()) {
        reject(
            attemptId,
            batchId,
            replayId,
            observationId,
            observation,
            decision,
            mappingVersion,
            qualityRulesVersion);
        counts.rejected++;
        metric(ProcessingOutcome.REJECTED, decision.reasonCode().name(), replayId != null);
        continue;
      }

      if (isOutOfOrder(batch.facilityId(), observation)) {
        decision = decision.withFlag("OUT_OF_ORDER");
        meterRegistry.counter("processing_out_of_order_total").increment();
      }
      int identityInserted =
          jdbc.update(
              """
              INSERT INTO telemetry_sample_identity (observation_id, observed_at, raw_batch_id, created_at)
              VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
              """,
              observationId,
              Timestamp.from(observation.observedAt()),
              batchId,
              Timestamp.from(started));
      if (identityInserted == 0) {
        counts.duplicate++;
        meterRegistry.counter("processing_duplicates_total").increment();
        metric(
            ProcessingOutcome.DUPLICATE, ReasonCode.DUPLICATE_OBSERVATION.name(), replayId != null);
        continue;
      }

      insertSample(
          batch,
          batchId,
          observationId,
          observation,
          decision,
          manifest.receivedAt(),
          started,
          mappingVersion,
          qualityRulesVersion);
      if (decision.outcome() == ProcessingOutcome.FLAGGED) {
        counts.flagged++;
      } else {
        counts.accepted++;
      }
      metric(decision.outcome(), "none", replayId != null);
      if (decision.quality() == dev.telemetrylab.contracts.Quality.GOOD
          && !observation.observedAt().isAfter(started)) {
        endToEndLag.record(Duration.between(observation.observedAt(), started).toMillis() / 1000.0);
      }
    }

    Instant completed = clock.instant();
    jdbc.update(
        """
        UPDATE processing_attempt SET status='COMPLETED', accepted_count=?, flagged_count=?,
          rejected_count=?, duplicate_count=?, completed_at=?
        WHERE processing_attempt_id=?
        """,
        counts.accepted,
        counts.flagged,
        counts.rejected,
        counts.duplicate,
        Timestamp.from(completed),
        attemptId);
    if (replayId == null) {
      completeLiveBatch(batchId, manifest.processingStatus(), counts);
    } else {
      completeReplayBatch(replayId, batchId, counts, completed);
    }
    meterRegistry.counter("processing_database_write_total", "result", "success").increment();
    LOGGER.info(
        "Raw batch processing committed; batchId={} replayId={} accepted={} flagged={} rejected={}"
            + " duplicate={}",
        batchId,
        replayId,
        counts.accepted,
        counts.flagged,
        counts.rejected,
        counts.duplicate);
    return counts;
  }

  private ObservationDecision prevalidate(
      RawObservation observation,
      String mappingVersion,
      String qualityRulesVersion,
      Instant receivedAt,
      Instant processedAt) {
    if (tooLong(observation.sourceSystem(), 64)
        || tooLong(observation.sourceEpoch(), 64)
        || tooLong(observation.sourceTag(), 256)
        || tooLong(observation.rawUnit(), 32)) {
      return ObservationDecision.rejected(
          ReasonCode.FIELD_TOO_LONG, "A raw observation field exceeds its configured limit");
    }
    return evaluator.evaluate(
        observation, mappingVersion, qualityRulesVersion, receivedAt, processedAt);
  }

  private void insertSample(
      RawObservationBatch batch,
      UUID batchId,
      String observationId,
      RawObservation observation,
      ObservationDecision decision,
      Instant receivedAt,
      Instant processedAt,
      String mappingVersion,
      String qualityRulesVersion) {
    String flags;
    try {
      flags = objectMapper.writeValueAsString(decision.flags());
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize quality flags", exception);
    }
    jdbc.update(
        """
        INSERT INTO telemetry_sample (
          observation_id, facility_id, asset_id, signal_id, observed_at, received_at,
          processed_at, value_double, unit, quality, flags, source_system, source_sequence,
          source_epoch, source_tag, collector_id, collector_version, mapping_version,
          quality_rules_version, raw_batch_id
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        observationId,
        batch.facilityId(),
        decision.mapping().assetId(),
        decision.mapping().signalId(),
        Timestamp.from(observation.observedAt()),
        Timestamp.from(receivedAt),
        Timestamp.from(processedAt),
        decision.value(),
        decision.mapping().canonicalUnit(),
        decision.quality().name(),
        flags,
        observation.sourceSystem(),
        observation.sourceSequence(),
        observation.sourceEpoch(),
        observation.sourceTag(),
        batch.collectorId(),
        batch.collectorVersion(),
        mappingVersion,
        qualityRulesVersion,
        batchId);
  }

  private void reject(
      UUID attemptId,
      UUID batchId,
      UUID replayId,
      String observationId,
      RawObservation observation,
      ObservationDecision decision,
      String mappingVersion,
      String qualityRulesVersion) {
    jdbc.update(
        """
        INSERT INTO telemetry_rejection (
          observation_id, batch_id, replay_id, source_tag, reason_code,
          human_readable_reason, mapping_version, quality_rules_version,
          processing_attempt_id, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
        """,
        observationId,
        batchId,
        replayId,
        observation.sourceTag(),
        decision.reasonCode().name(),
        decision.reason(),
        mappingVersion,
        qualityRulesVersion,
        attemptId,
        Timestamp.from(clock.instant()));
  }

  private boolean isOutOfOrder(String facilityId, RawObservation observation) {
    Long maximum =
        jdbc.queryForObject(
            """
            SELECT MAX(source_sequence) FROM telemetry_sample
            WHERE facility_id=? AND source_system=? AND source_epoch=? AND source_tag=?
            """,
            Long.class,
            facilityId,
            observation.sourceSystem(),
            observation.sourceEpoch(),
            observation.sourceTag());
    return maximum != null && observation.sourceSequence() < maximum;
  }

  private Manifest lockManifest(UUID batchId) {
    List<Manifest> manifests =
        jdbc.query(
            """
            SELECT received_at, processing_status, processing_attempt_count
            FROM ingestion_batch WHERE batch_id=? FOR UPDATE
            """,
            (result, row) ->
                new Manifest(
                    result.getTimestamp("received_at").toInstant(),
                    result.getString("processing_status"),
                    result.getInt("processing_attempt_count")),
            batchId);
    if (manifests.isEmpty()) {
      throw new IllegalStateException("Batch manifest is missing: " + batchId);
    }
    return manifests.getFirst();
  }

  private void completeLiveBatch(UUID batchId, String previousStatus, ProcessingCounts counts) {
    if ("PROCESSED".equals(previousStatus)) {
      jdbc.update(
          """
          UPDATE ingestion_batch
          SET processing_attempt_count=processing_attempt_count+1, last_error=NULL
          WHERE batch_id=?
          """,
          batchId);
      return;
    }
    jdbc.update(
        """
        UPDATE ingestion_batch
        SET processing_status='PROCESSED', accepted_count=?, flagged_count=?, rejected_count=?,
            duplicate_count=?, processing_attempt_count=processing_attempt_count+1, last_error=NULL
        WHERE batch_id=?
        """,
        counts.accepted,
        counts.flagged,
        counts.rejected,
        counts.duplicate,
        batchId);
  }

  private void completeReplayBatch(
      UUID replayId, UUID batchId, ProcessingCounts counts, Instant completed) {
    jdbc.update(
        """
        UPDATE replay_batch SET status='COMPLETED', processed_observation_count=?,
          accepted_count=?, flagged_count=?, rejected_count=?, duplicate_count=?, last_error=NULL
        WHERE replay_id=? AND batch_id=?
        """,
        counts.total(),
        counts.accepted,
        counts.flagged,
        counts.rejected,
        counts.duplicate,
        replayId,
        batchId);
    jdbc.update(
        """
        UPDATE replay_run r SET
          processed_observation_count=s.processed,
          accepted_count=s.accepted,
          flagged_count=s.flagged,
          rejected_count=s.rejected,
          duplicate_count=s.duplicate,
          status=CASE WHEN s.remaining=0 THEN 'COMPLETED' ELSE 'RUNNING' END,
          completed_at=CASE WHEN s.remaining=0 THEN ? ELSE NULL END
        FROM (
          SELECT replay_id,
                 COALESCE(SUM(processed_observation_count),0)::int AS processed,
                 COALESCE(SUM(accepted_count),0)::int AS accepted,
                 COALESCE(SUM(flagged_count),0)::int AS flagged,
                 COALESCE(SUM(rejected_count),0)::int AS rejected,
                 COALESCE(SUM(duplicate_count),0)::int AS duplicate,
                 COUNT(*) FILTER (WHERE status <> 'COMPLETED') AS remaining
          FROM replay_batch WHERE replay_id=? GROUP BY replay_id
        ) s
        WHERE r.replay_id=s.replay_id
        """,
        Timestamp.from(completed),
        replayId);
  }

  private void verifyRawObject(BatchReference reference, RawObjectStore.RawObject rawObject) {
    String calculated = ContentDigest.checksum(rawObject.content());
    if (!calculated.equals(reference.checksum())
        || !calculated.equals(rawObject.metadata().checksum())) {
      throw new IllegalStateException("Raw object checksum verification failed");
    }
  }

  private RawObservationBatch decode(byte[] compressed) {
    try {
      byte[] json = GzipCodec.decompress(compressed, properties.maximumDecompressedBytes());
      return objectMapper.readValue(json, RawObservationBatch.class);
    } catch (IOException exception) {
      throw new IllegalStateException("Stored raw batch is not valid gzip JSON", exception);
    }
  }

  private void verifyWorkerEnvelope(BatchReference reference, RawObservationBatch batch) {
    if (!ContractVersions.RAW_BATCH_V1.equals(batch.contractVersion())
        || !reference.batchId().equals(batch.batchId())
        || batch.observations().isEmpty()
        || batch.observations().size() > properties.maximumObservations()) {
      throw new IllegalStateException("Stored raw batch violates the worker envelope limits");
    }
  }

  private static boolean insideReplay(Instant observedAt, Instant from, Instant to) {
    return from == null || to == null || (!observedAt.isBefore(from) && observedAt.isBefore(to));
  }

  private static String stableId(String facilityId, RawObservation observation) {
    return DeterministicIds.observationId(
        safe(facilityId),
        safe(observation.sourceSystem()),
        safe(observation.sourceEpoch()),
        observation.sourceSequence(),
        safe(observation.sourceTag()));
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  private static boolean tooLong(String value, int maximum) {
    return value == null || value.isBlank() || value.length() > maximum;
  }

  private static Instant parseOptionalInstant(String value) {
    return value == null ? null : Instant.parse(value);
  }

  private void metric(ProcessingOutcome outcome, String reason, boolean replay) {
    meterRegistry
        .counter(
            "processing_observations_total",
            "outcome",
            outcome.name().toLowerCase(),
            "reason",
            reason.toLowerCase())
        .increment();
    if (replay) {
      meterRegistry
          .counter("replay_observations_total", "outcome", outcome.name().toLowerCase())
          .increment();
    }
  }

  private static String defaulted(String value, String fallback) {
    return value == null ? fallback : value;
  }

  private static String truncate(String value) {
    return value.length() <= 1000 ? value : value.substring(0, 1000);
  }

  private record Manifest(
      Instant receivedAt, String processingStatus, int processingAttemptCount) {}

  public static final class ProcessingCounts {
    private int accepted;
    private int flagged;
    private int rejected;
    private int duplicate;

    public int accepted() {
      return accepted;
    }

    public int flagged() {
      return flagged;
    }

    public int rejected() {
      return rejected;
    }

    public int duplicate() {
      return duplicate;
    }

    public int total() {
      return accepted + flagged + rejected + duplicate;
    }
  }
}
