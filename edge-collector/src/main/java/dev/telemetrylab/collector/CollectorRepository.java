package dev.telemetrylab.collector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.telemetrylab.contracts.ContentDigest;
import dev.telemetrylab.contracts.ContractVersions;
import dev.telemetrylab.contracts.ControllerReading;
import dev.telemetrylab.contracts.ControllerReadingsPage;
import dev.telemetrylab.contracts.GzipCodec;
import dev.telemetrylab.contracts.RawObservation;
import dev.telemetrylab.contracts.RawObservationBatch;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class CollectorRepository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper objectMapper;
  private final CollectorProperties properties;
  private final Clock clock;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "These mutable collaborators are application-scoped Spring infrastructure beans")
  public CollectorRepository(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      ObjectMapper objectMapper,
      CollectorProperties properties,
      Clock clock) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.clock = clock;
  }

  public synchronized void recoverInterruptedUploads() {
    jdbc.update(
        "UPDATE outbound_batch SET state='READY', next_attempt_at=?, last_error=? WHERE"
            + " state='UPLOADING'",
        clock.instant().toString(),
        "Recovered an upload interrupted by collector shutdown");
  }

  public synchronized CollectorState state() {
    return jdbc.queryForObject(
        "SELECT source_epoch, source_cursor, gap_detected, gap_detail, polling_paused FROM"
            + " collector_state WHERE source_id=?",
        (result, row) ->
            new CollectorState(
                result.getString("source_epoch"),
                result.getLong("source_cursor"),
                result.getBoolean("gap_detected"),
                result.getString("gap_detail"),
                result.getBoolean("polling_paused")),
        properties.sourceSystem());
  }

  @WithSpan("collector.sqlite.persist_page")
  public synchronized int persistPage(ControllerReadingsPage page) {
    Integer inserted =
        transactions.execute(
            status -> {
              int count = 0;
              for (ControllerReading reading : page.readings()) {
                count +=
                    jdbc.update(
                        """
                        INSERT INTO spool_observation (
                          source_system, source_epoch, source_sequence, source_tag, observed_at,
                          raw_value_json, raw_unit, source_quality_code, persisted_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        properties.sourceSystem(),
                        page.sourceEpoch(),
                        reading.sequence(),
                        reading.tag(),
                        reading.observedAt().toString(),
                        Double.toString(reading.value()),
                        reading.unit(),
                        reading.qualityCode(),
                        clock.instant().toString());
              }
              jdbc.update(
                  """
                  UPDATE collector_state
                  SET source_epoch=?, source_cursor=?, gap_detected=0, gap_detail=NULL, updated_at=?
                  WHERE source_id=?
                  """,
                  page.sourceEpoch(),
                  page.nextSequence(),
                  clock.instant().toString(),
                  properties.sourceSystem());
              return count;
            });
    return inserted == null ? 0 : inserted;
  }

  public synchronized void markGap(String detail) {
    jdbc.update(
        "UPDATE collector_state SET gap_detected=1, gap_detail=?, updated_at=? WHERE source_id=?",
        detail,
        clock.instant().toString(),
        properties.sourceSystem());
  }

  public synchronized void recoverGap(String sourceEpoch, long cursor) {
    jdbc.update(
        """
        UPDATE collector_state
        SET source_epoch=?, source_cursor=?, gap_detected=0, gap_detail=NULL, updated_at=?
        WHERE source_id=?
        """,
        sourceEpoch,
        cursor,
        clock.instant().toString(),
        properties.sourceSystem());
  }

  public synchronized long unacknowledgedCount() {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM spool_observation WHERE acknowledged_at IS NULL", Long.class);
    return count == null ? 0 : count;
  }

  public synchronized long oldestUnsentAgeSeconds() {
    String value =
        jdbc.queryForObject(
            "SELECT MIN(observed_at) FROM spool_observation WHERE acknowledged_at IS NULL",
            String.class);
    if (value == null) {
      return 0;
    }
    return Math.max(
        0, java.time.Duration.between(Instant.parse(value), clock.instant()).toSeconds());
  }

  public synchronized void setPollingPaused(boolean paused) {
    jdbc.update(
        "UPDATE collector_state SET polling_paused=?, updated_at=? WHERE source_id=?",
        paused ? 1 : 0,
        clock.instant().toString(),
        properties.sourceSystem());
  }

  public synchronized Optional<OutboundBatch> createBatchIfReady() {
    List<SpoolRow> candidates =
        jdbc.query(
            """
            SELECT s.id, s.source_system, s.source_epoch, s.source_sequence, s.source_tag,
                   s.observed_at, s.persisted_at, s.raw_value_json, s.raw_unit, s.source_quality_code
            FROM spool_observation s
            WHERE s.acknowledged_at IS NULL
              AND NOT EXISTS (
                SELECT 1 FROM outbound_batch_item i WHERE i.observation_id=s.id
              )
            ORDER BY s.id
            LIMIT ?
            """,
            CollectorRepository::spoolRow,
            properties.batchSize());
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    boolean full = candidates.size() >= properties.batchSize();
    boolean oldEnough =
        candidates
            .getFirst()
            .persistedAt()
            .plus(properties.batchMaximumAge())
            .isBefore(clock.instant());
    if (!full && !oldEnough) {
      return Optional.empty();
    }

    String batchId = UUID.randomUUID().toString();
    RawObservationBatch contract = toContract(batchId, candidates);
    byte[] json;
    try {
      json = objectMapper.writeValueAsBytes(contract);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize durable outbound batch", exception);
    }
    byte[] compressed = GzipCodec.compress(json);
    String contentDigest = ContentDigest.header(compressed);
    String checksum = ContentDigest.checksum(compressed);
    Instant createdAt = clock.instant();
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              """
              INSERT INTO outbound_batch (
                batch_id, compressed_payload, content_digest, checksum, observation_count,
                created_at, state, attempt_count, next_attempt_at
              ) VALUES (?, ?, ?, ?, ?, ?, 'READY', 0, ?)
              """,
              batchId,
              compressed,
              contentDigest,
              checksum,
              candidates.size(),
              createdAt.toString(),
              createdAt.toString());
          int order = 0;
          for (SpoolRow candidate : candidates) {
            jdbc.update(
                "INSERT INTO outbound_batch_item (batch_id, observation_id, item_order) VALUES (?,"
                    + " ?, ?)",
                batchId,
                candidate.id(),
                order++);
          }
        });
    return Optional.of(
        new OutboundBatch(
            batchId, compressed, contentDigest, checksum, candidates.size(), 0, createdAt));
  }

  public synchronized Optional<OutboundBatch> claimNextUpload() {
    List<OutboundBatch> due =
        jdbc.query(
            """
            SELECT batch_id, compressed_payload, content_digest, checksum,
                   observation_count, attempt_count, created_at
            FROM outbound_batch
            WHERE state IN ('READY', 'FAILED') AND next_attempt_at <= ?
            ORDER BY created_at
            LIMIT 1
            """,
            (result, row) ->
                new OutboundBatch(
                    result.getString("batch_id"),
                    result.getBytes("compressed_payload"),
                    result.getString("content_digest"),
                    result.getString("checksum"),
                    result.getInt("observation_count"),
                    result.getInt("attempt_count"),
                    Instant.parse(result.getString("created_at"))),
            clock.instant().toString());
    if (due.isEmpty()) {
      return Optional.empty();
    }
    OutboundBatch batch = due.getFirst();
    int claimed =
        jdbc.update(
            "UPDATE outbound_batch SET state='UPLOADING', attempt_count=attempt_count+1 WHERE"
                + " batch_id=? AND state IN ('READY','FAILED')",
            batch.batchId());
    return claimed == 1
        ? Optional.of(
            new OutboundBatch(
                batch.batchId(),
                batch.compressedPayload(),
                batch.contentDigest(),
                batch.checksum(),
                batch.observationCount(),
                batch.attemptCount() + 1,
                batch.createdAt()))
        : Optional.empty();
  }

  public synchronized void acknowledge(String batchId) {
    Instant now = clock.instant();
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "UPDATE outbound_batch SET state='ACKNOWLEDGED', acknowledged_at=?, last_error=NULL"
                  + " WHERE batch_id=?",
              now.toString(),
              batchId);
          jdbc.update(
              """
              UPDATE spool_observation SET acknowledged_at=?
              WHERE id IN (SELECT observation_id FROM outbound_batch_item WHERE batch_id=?)
              """,
              now.toString(),
              batchId);
        });
  }

  public synchronized void fail(String batchId, Instant nextAttemptAt, String error) {
    jdbc.update(
        "UPDATE outbound_batch SET state='FAILED', next_attempt_at=?, last_error=? WHERE"
            + " batch_id=?",
        nextAttemptAt.toString(),
        truncate(error, 1000),
        batchId);
  }

  public synchronized void pruneAcknowledged() {
    String cutoff = clock.instant().minus(properties.acknowledgedRetention()).toString();
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "DELETE FROM outbound_batch WHERE state='ACKNOWLEDGED' AND acknowledged_at < ?",
              cutoff);
          jdbc.update(
              "DELETE FROM spool_observation WHERE acknowledged_at IS NOT NULL AND acknowledged_at"
                  + " < ?",
              cutoff);
        });
  }

  public synchronized long pendingBatchCount() {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbound_batch WHERE state <> 'ACKNOWLEDGED'", Long.class);
    return count == null ? 0 : count;
  }

  private RawObservationBatch toContract(String batchId, List<SpoolRow> candidates) {
    List<RawObservation> observations = new ArrayList<>(candidates.size());
    for (SpoolRow row : candidates) {
      try {
        observations.add(
            new RawObservation(
                row.sourceSystem(),
                row.sourceEpoch(),
                row.sourceSequence(),
                row.sourceTag(),
                row.observedAt(),
                objectMapper.readTree(row.rawValueJson()),
                row.rawUnit(),
                row.sourceQualityCode()));
      } catch (JsonProcessingException exception) {
        throw new IllegalStateException("Spool contains invalid raw JSON", exception);
      }
    }
    return new RawObservationBatch(
        ContractVersions.RAW_BATCH_V1,
        batchId,
        properties.collectorId(),
        properties.collectorVersion(),
        properties.facilityId(),
        clock.instant(),
        observations);
  }

  private static SpoolRow spoolRow(ResultSet result, int rowNumber) throws SQLException {
    return new SpoolRow(
        result.getLong("id"),
        result.getString("source_system"),
        result.getString("source_epoch"),
        result.getLong("source_sequence"),
        result.getString("source_tag"),
        Instant.parse(result.getString("observed_at")),
        Instant.parse(result.getString("persisted_at")),
        result.getString("raw_value_json"),
        result.getString("raw_unit"),
        result.getInt("source_quality_code"));
  }

  private static String truncate(String value, int maximumLength) {
    if (value == null || value.length() <= maximumLength) {
      return value;
    }
    return value.substring(0, maximumLength);
  }
}
