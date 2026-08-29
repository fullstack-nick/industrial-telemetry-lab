package dev.telemetrylab.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.telemetrylab.contracts.ContractObjectMapper;
import dev.telemetrylab.contracts.ControllerReading;
import dev.telemetrylab.contracts.ControllerReadingsPage;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

class CollectorDurabilityTest {
  @TempDir Path temporaryDirectory;

  @Test
  void cursorAndExactBatchBytesSurviveAnInterruptedUpload() {
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl(
        "jdbc:sqlite:"
            + temporaryDirectory.resolve("collector.db")
            + "?journal_mode=WAL&synchronous=FULL&foreign_keys=on&busy_timeout=5000");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    TransactionTemplate transactions =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    Clock clock = Clock.fixed(Instant.parse("2026-08-29T10:00:00Z"), ZoneOffset.UTC);
    CollectorProperties properties = properties();
    ObjectMapper mapper = ContractObjectMapper.create();
    CollectorRepository first =
        new CollectorRepository(jdbc, transactions, mapper, properties, clock);
    ControllerReading reading =
        new ControllerReading(
            1, "CTRL_A.ZONE[01].TEMP_PV", Instant.parse("2026-08-29T09:59:50Z"), 24.5, "degC", 192);

    first.persistPage(
        new ControllerReadingsPage("47af63a8-85c4-4a34-9b60-f63d6e16f564", 1, List.of(reading)));
    OutboundBatch created = first.createBatchIfReady().orElseThrow();
    OutboundBatch interrupted = first.claimNextUpload().orElseThrow();

    CollectorRepository restarted =
        new CollectorRepository(jdbc, transactions, mapper, properties, clock);
    restarted.recoverInterruptedUploads();
    OutboundBatch retried = restarted.claimNextUpload().orElseThrow();

    assertThat(restarted.state().sourceCursor()).isEqualTo(1);
    assertThat(restarted.unacknowledgedCount()).isEqualTo(1);
    assertThat(interrupted.batchId()).isEqualTo(created.batchId()).isEqualTo(retried.batchId());
    assertThat(retried.compressedPayload()).containsExactly(created.compressedPayload());
    assertThat(retried.checksum()).isEqualTo(created.checksum());
    assertThat(filesExist(temporaryDirectory)).contains("collector.db");
  }

  private static List<String> filesExist(Path directory) {
    try (var stream = java.nio.file.Files.list(directory)) {
      return stream.map(path -> path.getFileName().toString()).toList();
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static CollectorProperties properties() {
    return new CollectorProperties(
        "edge-gateway-01",
        "1.0.0",
        "facility-alpha-config-1.0.0",
        "http-controller-adapter-1.0.0",
        "facility-alpha",
        "controller-a",
        "http://source",
        "http://gateway",
        "token",
        500,
        1,
        Duration.ofSeconds(5),
        100_000,
        80,
        60,
        Duration.ofMinutes(15));
  }
}
