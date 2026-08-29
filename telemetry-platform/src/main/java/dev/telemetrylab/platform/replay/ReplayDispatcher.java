package dev.telemetrylab.platform.replay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.telemetrylab.contracts.BatchReference;
import dev.telemetrylab.platform.messaging.ConfirmedPublisher;
import dev.telemetrylab.platform.messaging.RabbitTopology;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(
    name = "telemetry.platform.mode",
    havingValue = "gateway",
    matchIfMissing = true)
class ReplayDispatcher {
  private static final Logger LOGGER = LoggerFactory.getLogger(ReplayDispatcher.class);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ConfirmedPublisher publisher;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final MeterRegistry meterRegistry;

  ReplayDispatcher(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      ConfirmedPublisher publisher,
      ObjectMapper objectMapper,
      Clock clock,
      MeterRegistry meterRegistry) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.publisher = publisher;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.meterRegistry = meterRegistry;
  }

  @Scheduled(fixedDelayString = "${telemetry.replay.dispatch-delay-ms:100}")
  void dispatchNext() {
    transactions.executeWithoutResult(
        status -> {
          List<ReplayDispatch> candidates =
              jdbc.query(
                  """
                  SELECT rb.replay_id, rb.batch_id, ib.object_key, ib.checksum,
                         rr.mapping_version, rr.quality_rules_version, rr.from_time, rr.to_time
                  FROM replay_batch rb
                  JOIN replay_run rr ON rr.replay_id=rb.replay_id
                  JOIN ingestion_batch ib ON ib.batch_id=rb.batch_id
                  WHERE rb.status='PENDING' AND rr.status IN ('PENDING','RUNNING')
                  ORDER BY rr.requested_at, ib.received_at
                  FOR UPDATE OF rb SKIP LOCKED
                  LIMIT 1
                  """,
                  (result, row) ->
                      new ReplayDispatch(
                          result.getObject("replay_id", UUID.class),
                          result.getObject("batch_id", UUID.class),
                          result.getString("object_key"),
                          result.getString("checksum"),
                          result.getString("mapping_version"),
                          result.getString("quality_rules_version"),
                          result.getTimestamp("from_time").toInstant(),
                          result.getTimestamp("to_time").toInstant()));
          if (candidates.isEmpty()) {
            return;
          }
          ReplayDispatch candidate = candidates.getFirst();
          BatchReference reference =
              new BatchReference(
                  "ReplayRawBatch",
                  candidate.batchId().toString(),
                  candidate.objectKey(),
                  candidate.checksum(),
                  candidate.replayId().toString(),
                  candidate.mappingVersion(),
                  candidate.qualityRulesVersion(),
                  candidate.from().toString(),
                  candidate.to().toString());
          try {
            String json = objectMapper.writeValueAsString(reference);
            publisher.publish(
                RabbitTopology.MAIN,
                json,
                Map.of("x-processing-attempt", 0, "x-replay", true),
                Duration.ofSeconds(10));
            jdbc.update(
                "UPDATE replay_batch SET status='DISPATCHED', last_error=NULL WHERE replay_id=? AND"
                    + " batch_id=?",
                candidate.replayId(),
                candidate.batchId());
            jdbc.update(
                """
                UPDATE replay_run SET status='RUNNING', started_at=COALESCE(started_at, ?)
                WHERE replay_id=?
                """,
                Timestamp.from(clock.instant()),
                candidate.replayId());
            meterRegistry.counter("replay_batches_total", "result", "dispatched").increment();
          } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize replay reference", exception);
          } catch (RuntimeException exception) {
            jdbc.update(
                "UPDATE replay_batch SET last_error=? WHERE replay_id=? AND batch_id=?",
                truncate(exception.toString()),
                candidate.replayId(),
                candidate.batchId());
            meterRegistry.counter("replay_batches_total", "result", "failure").increment();
            LOGGER.warn(
                "Replay dispatch failed and remains pending; replayId={} batchId={} errorType={}",
                candidate.replayId(),
                candidate.batchId(),
                exception.getClass().getSimpleName());
          }
        });
  }

  private static String truncate(String value) {
    return value.length() <= 1000 ? value : value.substring(0, 1000);
  }

  private record ReplayDispatch(
      UUID replayId,
      UUID batchId,
      String objectKey,
      String checksum,
      String mappingVersion,
      String qualityRulesVersion,
      java.time.Instant from,
      java.time.Instant to) {}
}
