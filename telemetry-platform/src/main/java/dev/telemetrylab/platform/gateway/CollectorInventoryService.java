package dev.telemetrylab.platform.gateway;

import dev.telemetrylab.contracts.CollectorHeartbeat;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
    name = "telemetry.platform.mode",
    havingValue = "gateway",
    matchIfMissing = true)
class CollectorInventoryService {
  private final JdbcTemplate jdbc;
  private final Clock clock;

  CollectorInventoryService(JdbcTemplate jdbc, Clock clock, MeterRegistry meterRegistry) {
    this.jdbc = jdbc;
    this.clock = clock;
    Gauge.builder(
            "collector_last_heartbeat_age_seconds", this, CollectorInventoryService::heartbeatAge)
        .description("Age of the most recent persisted collector heartbeat")
        .register(meterRegistry);
    Gauge.builder(
            "collector_spool_observations", this, CollectorInventoryService::spoolObservations)
        .description("Durable spool observations reported by all collectors")
        .register(meterRegistry);
    Gauge.builder(
            "collector_oldest_unsent_age_seconds", this, CollectorInventoryService::oldestUnsentAge)
        .description("Oldest unsent observation age reported by any collector")
        .register(meterRegistry);
  }

  void heartbeat(String collectorId, CollectorHeartbeat heartbeat) {
    Instant now = clock.instant();
    String status = heartbeat.sourceConnected() ? "ONLINE" : "SOURCE_DISCONNECTED";
    jdbc.update(
        """
        INSERT INTO collector_status (
          collector_id, collector_version, configuration_version, source_adapter_version,
          last_heartbeat, last_successful_upload_at, spool_observation_count,
          oldest_unsent_observation_age_seconds, source_connected, current_status
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (collector_id) DO UPDATE SET
          collector_version=EXCLUDED.collector_version,
          configuration_version=EXCLUDED.configuration_version,
          source_adapter_version=EXCLUDED.source_adapter_version,
          last_heartbeat=EXCLUDED.last_heartbeat,
          last_successful_upload_at=EXCLUDED.last_successful_upload_at,
          spool_observation_count=EXCLUDED.spool_observation_count,
          oldest_unsent_observation_age_seconds=EXCLUDED.oldest_unsent_observation_age_seconds,
          source_connected=EXCLUDED.source_connected,
          current_status=EXCLUDED.current_status
        """,
        collectorId,
        heartbeat.collectorVersion(),
        heartbeat.configurationVersion(),
        heartbeat.sourceAdapterVersion(),
        Timestamp.from(now),
        heartbeat.lastSuccessfulUploadAt() == null
            ? null
            : Timestamp.from(heartbeat.lastSuccessfulUploadAt()),
        heartbeat.spoolObservationCount(),
        heartbeat.oldestUnsentObservationAgeSeconds(),
        heartbeat.sourceConnected(),
        status);
  }

  List<Map<String, Object>> all() {
    return jdbc.queryForList(
        """
        SELECT collector_id AS "collectorId", collector_version AS "collectorVersion",
               configuration_version AS "configurationVersion",
               source_adapter_version AS "sourceAdapterVersion",
               last_heartbeat AS "lastHeartbeat",
               last_successful_upload_at AS "lastSuccessfulUploadAt",
               spool_observation_count AS "spoolObservationCount",
               oldest_unsent_observation_age_seconds AS "oldestUnsentObservationAgeSeconds",
               source_connected AS "sourceConnected",
               CASE WHEN last_heartbeat < now() - INTERVAL '90 seconds' THEN 'OFFLINE'
                    ELSE current_status END AS "currentStatus"
        FROM collector_status ORDER BY collector_id
        """);
  }

  Map<String, Object> one(String collectorId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            """
            SELECT collector_id AS "collectorId", collector_version AS "collectorVersion",
                   configuration_version AS "configurationVersion",
                   source_adapter_version AS "sourceAdapterVersion",
                   last_heartbeat AS "lastHeartbeat",
                   last_successful_upload_at AS "lastSuccessfulUploadAt",
                   spool_observation_count AS "spoolObservationCount",
                   oldest_unsent_observation_age_seconds AS "oldestUnsentObservationAgeSeconds",
                   source_connected AS "sourceConnected",
                   CASE WHEN last_heartbeat < now() - INTERVAL '90 seconds' THEN 'OFFLINE'
                        ELSE current_status END AS "currentStatus"
            FROM collector_status WHERE collector_id=?
            """,
            collectorId);
    if (rows.isEmpty()) {
      throw new dev.telemetrylab.platform.PlatformProblemException(
          org.springframework.http.HttpStatus.NOT_FOUND,
          "COLLECTOR_NOT_FOUND",
          "No collector inventory record exists for " + collectorId);
    }
    return rows.getFirst();
  }

  private double heartbeatAge() {
    try {
      Timestamp last =
          jdbc.queryForObject("SELECT MAX(last_heartbeat) FROM collector_status", Timestamp.class);
      return last == null
          ? Double.POSITIVE_INFINITY
          : Math.max(0, Duration.between(last.toInstant(), clock.instant()).toSeconds());
    } catch (RuntimeException exception) {
      return Double.NaN;
    }
  }

  private double spoolObservations() {
    try {
      Long count =
          jdbc.queryForObject(
              "SELECT COALESCE(SUM(spool_observation_count), 0) FROM collector_status", Long.class);
      return count == null ? 0 : count;
    } catch (RuntimeException exception) {
      return Double.NaN;
    }
  }

  private double oldestUnsentAge() {
    try {
      Long age =
          jdbc.queryForObject(
              "SELECT COALESCE(MAX(oldest_unsent_observation_age_seconds), 0) FROM"
                  + " collector_status",
              Long.class);
      return age == null ? 0 : age;
    } catch (RuntimeException exception) {
      return Double.NaN;
    }
  }
}
