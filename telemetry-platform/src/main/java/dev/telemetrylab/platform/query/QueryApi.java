package dev.telemetrylab.platform.query;

import dev.telemetrylab.platform.PlatformProblemException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(
    name = "telemetry.platform.mode",
    havingValue = "gateway",
    matchIfMissing = true)
class QueryApi {
  private final SignalRegistry signalRegistry;
  private final TelemetryQueryService telemetry;
  private final JdbcTemplate jdbc;

  QueryApi(SignalRegistry signalRegistry, TelemetryQueryService telemetry, JdbcTemplate jdbc) {
    this.signalRegistry = signalRegistry;
    this.telemetry = telemetry;
    this.jdbc = jdbc;
  }

  @GetMapping("/signals")
  Map<String, Object> signals() {
    return signalRegistry.document();
  }

  @GetMapping("/telemetry")
  TelemetryQueryService.TelemetryPage telemetry(
      @RequestParam String facilityId,
      @RequestParam(required = false) String assetId,
      @RequestParam(required = false) String signalId,
      @RequestParam java.time.Instant from,
      @RequestParam java.time.Instant to,
      @RequestParam(defaultValue = "true") Boolean includeFlagged,
      @RequestParam(defaultValue = "true") Boolean includeBadQuality,
      @RequestParam(required = false) Integer pageSize,
      @RequestParam(required = false) String cursor) {
    return telemetry.query(
        facilityId,
        assetId,
        signalId,
        from,
        to,
        includeFlagged,
        includeBadQuality,
        pageSize,
        cursor);
  }

  @GetMapping("/batches/{batchId}")
  Map<String, Object> batch(@PathVariable UUID batchId) {
    List<Map<String, Object>> batches =
        jdbc.queryForList(
            """
            SELECT batch_id AS "batchId", collector_id AS "collectorId",
                   facility_id AS "facilityId", checksum, object_key AS "objectKey",
                   received_at AS "receivedAt", minimum_observed_at AS "minimumObservedAt",
                   maximum_observed_at AS "maximumObservedAt", observation_count AS "observationCount",
                   processing_status AS "processingStatus", accepted_count AS "acceptedCount",
                   flagged_count AS "flaggedCount", rejected_count AS "rejectedCount",
                   duplicate_count AS "duplicateCount", processing_attempt_count AS "processingAttemptCount",
                   last_error AS "lastError"
            FROM ingestion_batch WHERE batch_id=?
            """,
            batchId);
    if (batches.isEmpty()) {
      throw new PlatformProblemException(
          HttpStatus.NOT_FOUND, "BATCH_NOT_FOUND", "No batch exists with ID " + batchId);
    }
    LinkedHashMap<String, Object> response = new LinkedHashMap<>(batches.getFirst());
    response.put(
        "attempts",
        jdbc.queryForList(
            """
            SELECT processing_attempt_id AS "processingAttemptId", replay_id AS "replayId",
                   mapping_version AS "mappingVersion", quality_rules_version AS "qualityRulesVersion",
                   attempt_number AS "attemptNumber", status, accepted_count AS "acceptedCount",
                   flagged_count AS "flaggedCount", rejected_count AS "rejectedCount",
                   duplicate_count AS "duplicateCount", started_at AS "startedAt",
                   completed_at AS "completedAt", last_error AS "lastError"
            FROM processing_attempt WHERE batch_id=? ORDER BY started_at DESC
            """,
            batchId));
    response.put(
        "rejections",
        jdbc.queryForList(
            """
            SELECT observation_id AS "observationId", replay_id AS "replayId",
                   source_tag AS "sourceTag", reason_code AS "reasonCode",
                   human_readable_reason AS "reason", mapping_version AS "mappingVersion",
                   quality_rules_version AS "qualityRulesVersion", created_at AS "createdAt"
            FROM telemetry_rejection WHERE batch_id=? ORDER BY created_at
            """,
            batchId));
    return response;
  }
}
