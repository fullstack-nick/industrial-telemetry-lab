package dev.telemetrylab.platform.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
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
class OutboxPublisher {
  private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ConfirmedPublisher publisher;
  private final Clock clock;
  private final MeterRegistry meterRegistry;

  OutboxPublisher(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      ConfirmedPublisher publisher,
      Clock clock,
      MeterRegistry meterRegistry) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.publisher = publisher;
    this.clock = clock;
    this.meterRegistry = meterRegistry;
    Gauge.builder("outbox_unpublished_events", this, OutboxPublisher::safeCount)
        .register(meterRegistry);
    Gauge.builder("outbox_oldest_unpublished_age_seconds", this, OutboxPublisher::safeOldestAge)
        .register(meterRegistry);
  }

  @Scheduled(fixedDelayString = "${telemetry.outbox.publish-delay-ms:250}")
  @WithSpan("gateway.outbox.publish")
  void publishNext() {
    transactions.executeWithoutResult(
        status -> {
          List<OutboxRecord> rows =
              jdbc.query(
                  """
                  SELECT event_id, payload::text AS payload, trace_parent, attempt_count
                  FROM outbox_event
                  WHERE published_at IS NULL AND next_attempt_at <= now()
                  ORDER BY created_at
                  FOR UPDATE SKIP LOCKED
                  LIMIT 1
                  """,
                  (result, row) ->
                      new OutboxRecord(
                          result.getObject("event_id", UUID.class),
                          result.getString("payload"),
                          result.getString("trace_parent"),
                          result.getInt("attempt_count")));
          if (rows.isEmpty()) {
            return;
          }
          OutboxRecord record = rows.getFirst();
          try {
            Map<String, Object> headers = new HashMap<>();
            if (record.traceParent() != null && !record.traceParent().isBlank()) {
              headers.put("traceparent", record.traceParent());
            }
            headers.put("x-processing-attempt", 0);
            publisher.publish(
                RabbitTopology.MAIN, record.payload(), headers, Duration.ofSeconds(10));
            jdbc.update(
                "UPDATE outbox_event SET published_at=?, attempt_count=attempt_count+1,"
                    + " last_error=NULL WHERE event_id=?",
                Timestamp.from(clock.instant()),
                record.eventId());
            meterRegistry.counter("outbox_publish_total", "result", "success").increment();
          } catch (RuntimeException exception) {
            int attempts = record.attemptCount() + 1;
            long delaySeconds = Math.min(60, 1L << Math.min(attempts, 6));
            jdbc.update(
                """
                UPDATE outbox_event
                SET attempt_count=attempt_count+1, next_attempt_at=?, last_error=?
                WHERE event_id=?
                """,
                Timestamp.from(clock.instant().plusSeconds(delaySeconds)),
                truncate(exception.toString()),
                record.eventId());
            meterRegistry.counter("outbox_publish_total", "result", "failure").increment();
            LOGGER.warn(
                "Outbox publish failed and remains pending; eventId={} attempt={} errorType={}",
                record.eventId(),
                attempts,
                exception.getClass().getSimpleName());
          }
        });
  }

  private double safeCount() {
    try {
      Long value =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL", Long.class);
      return value == null ? 0 : value;
    } catch (RuntimeException exception) {
      return Double.NaN;
    }
  }

  private double safeOldestAge() {
    try {
      Timestamp oldest =
          jdbc.queryForObject(
              "SELECT MIN(created_at) FROM outbox_event WHERE published_at IS NULL",
              Timestamp.class);
      return oldest == null
          ? 0
          : Math.max(0, Duration.between(oldest.toInstant(), clock.instant()).toSeconds());
    } catch (RuntimeException exception) {
      return Double.NaN;
    }
  }

  private static String truncate(String value) {
    return value.length() <= 1000 ? value : value.substring(0, 1000);
  }

  private record OutboxRecord(UUID eventId, String payload, String traceParent, int attemptCount) {}
}
