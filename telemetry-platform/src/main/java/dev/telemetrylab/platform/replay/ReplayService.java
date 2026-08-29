package dev.telemetrylab.platform.replay;

import dev.telemetrylab.contracts.ReplayRequest;
import dev.telemetrylab.platform.PlatformProblemException;
import dev.telemetrylab.platform.PlatformProperties;
import dev.telemetrylab.platform.worker.MappingCatalog;
import dev.telemetrylab.platform.worker.QualityCatalog;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class ReplayService {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final MappingCatalog mappings;
  private final QualityCatalog qualityRules;
  private final PlatformProperties properties;
  private final Clock clock;
  private final MeterRegistry meterRegistry;

  ReplayService(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      MappingCatalog mappings,
      QualityCatalog qualityRules,
      PlatformProperties properties,
      Clock clock,
      MeterRegistry meterRegistry) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.mappings = mappings;
    this.qualityRules = qualityRules;
    this.properties = properties;
    this.clock = clock;
    this.meterRegistry = meterRegistry;
  }

  Map<String, Object> create(ReplayRequest request) {
    validate(request);
    String rulesVersion =
        request.qualityRulesVersion() == null
            ? properties.defaultQualityRulesVersion()
            : request.qualityRulesVersion();
    UUID replayId = UUID.randomUUID();
    Instant requestedAt = clock.instant();
    try {
      Map<String, Object> response =
          transactions.execute(
              status -> {
                jdbc.update(
                    """
                    INSERT INTO replay_run (
                      replay_id, facility_id, from_time, to_time, mapping_version,
                      quality_rules_version, reason, status, requested_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                    """,
                    replayId,
                    request.facilityId(),
                    Timestamp.from(request.from()),
                    Timestamp.from(request.to()),
                    request.mappingVersion(),
                    rulesVersion,
                    request.reason(),
                    Timestamp.from(requestedAt));
                List<UUID> batches =
                    jdbc.queryForList(
                        """
                        SELECT batch_id FROM ingestion_batch
                        WHERE facility_id=?
                          AND minimum_observed_at < ?
                          AND maximum_observed_at >= ?
                        ORDER BY received_at
                        """,
                        UUID.class,
                        request.facilityId(),
                        Timestamp.from(request.to()),
                        Timestamp.from(request.from()));
                for (UUID batchId : batches) {
                  jdbc.update(
                      "INSERT INTO replay_batch (replay_id, batch_id, status) VALUES (?, ?,"
                          + " 'PENDING')",
                      replayId,
                      batchId);
                }
                if (batches.isEmpty()) {
                  jdbc.update(
                      "UPDATE replay_run SET status='COMPLETED', completed_at=? WHERE replay_id=?",
                      Timestamp.from(requestedAt),
                      replayId);
                } else {
                  jdbc.update(
                      "UPDATE replay_run SET matching_batch_count=? WHERE replay_id=?",
                      batches.size(),
                      replayId);
                }
                return Map.of(
                    "replayId",
                    replayId,
                    "status",
                    batches.isEmpty() ? "COMPLETED" : "PENDING",
                    "matchingBatchCount",
                    batches.size(),
                    "mappingVersion",
                    request.mappingVersion(),
                    "qualityRulesVersion",
                    rulesVersion);
              });
      meterRegistry.counter("replay_runs_total", "result", "created").increment();
      return response;
    } catch (DataIntegrityViolationException exception) {
      meterRegistry.counter("replay_runs_total", "result", "rejected").increment();
      throw new PlatformProblemException(
          HttpStatus.CONFLICT, "REPLAY_ALREADY_ACTIVE", "Only one replay may be active at a time");
    }
  }

  Map<String, Object> get(UUID replayId) {
    List<Map<String, Object>> runs =
        jdbc.queryForList(
            """
            SELECT replay_id AS "replayId", facility_id AS "facilityId", from_time AS "from",
                   to_time AS "to", mapping_version AS "mappingVersion",
                   quality_rules_version AS "qualityRulesVersion", reason, status,
                   matching_batch_count AS "matchingBatchCount",
                   processed_observation_count AS "processedObservationCount",
                   accepted_count AS "acceptedCount", flagged_count AS "flaggedCount",
                   rejected_count AS "rejectedCount", duplicate_count AS "duplicateCount",
                   requested_at AS "requestedAt", started_at AS "startedAt",
                   completed_at AS "completedAt", last_error AS "lastError"
            FROM replay_run WHERE replay_id=?
            """,
            replayId);
    if (runs.isEmpty()) {
      throw new PlatformProblemException(
          HttpStatus.NOT_FOUND, "REPLAY_NOT_FOUND", "No replay exists with ID " + replayId);
    }
    LinkedHashMap<String, Object> response = new LinkedHashMap<>(runs.getFirst());
    response.put(
        "batches",
        jdbc.queryForList(
            """
            SELECT batch_id AS "batchId", status,
                   processed_observation_count AS "processedObservationCount",
                   accepted_count AS "acceptedCount", flagged_count AS "flaggedCount",
                   rejected_count AS "rejectedCount", duplicate_count AS "duplicateCount",
                   last_error AS "lastError"
            FROM replay_batch WHERE replay_id=? ORDER BY batch_id
            """,
            replayId));
    return response;
  }

  private void validate(ReplayRequest request) {
    if (request == null
        || request.facilityId() == null
        || request.facilityId().isBlank()
        || request.from() == null
        || request.to() == null
        || !request.to().isAfter(request.from())) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST,
          "INVALID_REPLAY_REQUEST",
          "A valid facility and time range are required");
    }
    if (Duration.between(request.from(), request.to()).compareTo(properties.maximumReplayRange())
        > 0) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST,
          "REPLAY_RANGE_TOO_LARGE",
          "Replay requests are limited to " + properties.maximumReplayRange());
    }
    if (!mappings.exists(request.mappingVersion())) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST,
          "MAPPING_VERSION_NOT_FOUND",
          "The selected mapping version is unavailable");
    }
    String rules =
        request.qualityRulesVersion() == null
            ? properties.defaultQualityRulesVersion()
            : request.qualityRulesVersion();
    if (!qualityRules.exists(rules)) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST,
          "QUALITY_RULES_VERSION_NOT_FOUND",
          "The selected quality-rules version is unavailable");
    }
    if (request.reason() == null || request.reason().isBlank() || request.reason().length() > 500) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST,
          "INVALID_REPLAY_REASON",
          "A replay reason of at most 500 characters is required");
    }
  }
}
