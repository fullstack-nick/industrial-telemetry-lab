package dev.telemetrylab.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.ConnectionFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Testcontainers
class PlatformInfrastructureIT {
  @Container
  static final PostgreSQLContainer TIMESCALE =
      new PostgreSQLContainer(
              DockerImageName.parse("timescale/timescaledb:2.29.2-pg17")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("telemetry")
          .withUsername("telemetry")
          .withPassword("local-test-password");

  @Container
  static final RabbitMQContainer RABBIT =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"))
          .withAdminUser("telemetry")
          .withAdminPassword("local-test-password");

  @Container
  static final GenericContainer<?> SEAWEED =
      new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.44"))
          .withCommand("mini", "-dir=/data")
          .withEnv("AWS_ACCESS_KEY_ID", "telemetry-test-access")
          .withEnv("AWS_SECRET_ACCESS_KEY", "telemetry-test-secret")
          .withExposedPorts(8333)
          .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forListeningPort());

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  @Test
  void rawStorageAndConfirmedMessagingUseRealLocalDependencies() throws Exception {
    byte[] exact = "exact-gzip-placeholder-bytes".getBytes(StandardCharsets.UTF_8);
    try (S3Client s3 =
        S3Client.builder()
            .endpointOverride(
                URI.create("http://" + SEAWEED.getHost() + ":" + SEAWEED.getMappedPort(8333)))
            .forcePathStyle(true)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("telemetry-test-access", "telemetry-test-secret")))
            .region(Region.US_EAST_1)
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build()) {
      s3.createBucket(CreateBucketRequest.builder().bucket("telemetry-test").build());
      s3.putObject(
          PutObjectRequest.builder()
              .bucket("telemetry-test")
              .key("raw-observations/test.json.gz")
              .metadata(java.util.Map.of("sha256", "test-digest"))
              .build(),
          RequestBody.fromBytes(exact));
      assertThat(
              s3.getObjectAsBytes(
                      GetObjectRequest.builder()
                          .bucket("telemetry-test")
                          .key("raw-observations/test.json.gz")
                          .build())
                  .asByteArray())
          .containsExactly(exact);
    }

    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(RABBIT.getHost());
    factory.setPort(RABBIT.getAmqpPort());
    factory.setUsername("telemetry");
    factory.setPassword("local-test-password");
    try (var connection = factory.newConnection();
        var channel = connection.createChannel()) {
      String queue = "telemetry.integration." + UUID.randomUUID();
      channel.queueDeclare(queue, true, false, true, null);
      channel.confirmSelect();
      channel.basicPublish(
          "",
          queue,
          com.rabbitmq.client.MessageProperties.PERSISTENT_TEXT_PLAIN,
          "confirmed-reference".getBytes(StandardCharsets.UTF_8));
      channel.waitForConfirmsOrDie(10_000);
      assertThat(channel.basicGet(queue, true).getBody())
          .isEqualTo("confirmed-reference".getBytes(StandardCharsets.UTF_8));
    }
  }

  @Test
  void globalIdentityAndRejectionAuditRemainUniqueAcrossRetriesAndTimeChunks() throws Exception {
    UUID firstBatch = UUID.randomUUID();
    UUID secondBatch = UUID.randomUUID();
    String observationId = "a".repeat(64);
    try (Connection connection =
        DriverManager.getConnection(
            TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword())) {
      insertManifest(connection, firstBatch, "2026-08-29T10:00:00Z");
      insertManifest(connection, secondBatch, "2026-08-31T10:00:00Z");
      try (PreparedStatement identity =
          connection.prepareStatement(
              "INSERT INTO telemetry_sample_identity"
                  + " (observation_id, observed_at, raw_batch_id, created_at) VALUES (?, ?, ?, ?)"
                  + " ON CONFLICT DO NOTHING")) {
        identity.setString(1, observationId);
        identity.setObject(2, java.sql.Timestamp.from(Instant.parse("2026-08-29T10:00:00Z")));
        identity.setObject(3, firstBatch);
        identity.setObject(4, java.sql.Timestamp.from(Instant.now()));
        assertThat(identity.executeUpdate()).isEqualTo(1);
        identity.setObject(2, java.sql.Timestamp.from(Instant.parse("2026-08-31T10:00:00Z")));
        identity.setObject(3, secondBatch);
        assertThat(identity.executeUpdate()).isZero();
      }

      UUID attempt = UUID.randomUUID();
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO processing_attempt"
                  + " (processing_attempt_id,batch_id,mapping_version,quality_rules_version,"
                  + " attempt_number,status,started_at) VALUES (?,?,?,?,1,'COMPLETED',?)")) {
        statement.setObject(1, attempt);
        statement.setObject(2, firstBatch);
        statement.setString(3, "controller-a-mapping-1.0.0");
        statement.setString(4, "quality-rules-1.0.0");
        statement.setObject(5, java.sql.Timestamp.from(Instant.now()));
        statement.executeUpdate();
      }
      String rejectionSql =
          "INSERT INTO telemetry_rejection"
              + " (observation_id,batch_id,source_tag,reason_code,human_readable_reason,"
              + " mapping_version,quality_rules_version,processing_attempt_id,created_at)"
              + " VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING";
      try (PreparedStatement rejection = connection.prepareStatement(rejectionSql)) {
        for (int run = 0; run < 2; run++) {
          rejection.setString(1, "b".repeat(64));
          rejection.setObject(2, firstBatch);
          rejection.setString(3, "UNKNOWN.TAG");
          rejection.setString(4, "UNKNOWN_SOURCE_TAG");
          rejection.setString(5, "No mapping rule recognizes the source tag");
          rejection.setString(6, "controller-a-mapping-1.0.0");
          rejection.setString(7, "quality-rules-1.0.0");
          rejection.setObject(8, attempt);
          rejection.setObject(9, java.sql.Timestamp.from(Instant.now()));
          assertThat(rejection.executeUpdate()).isEqualTo(run == 0 ? 1 : 0);
        }
      }
      try (Statement statement = connection.createStatement();
          ResultSet result =
              statement.executeQuery(
                  "SELECT (SELECT count(*) FROM telemetry_sample_identity),"
                      + " (SELECT count(*) FROM telemetry_rejection)")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getLong(1)).isEqualTo(1);
        assertThat(result.getLong(2)).isEqualTo(1);
      }
    }
  }

  private static void insertManifest(Connection connection, UUID batchId, String observedAt)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO ingestion_batch"
                + " (batch_id,collector_id,collector_version,facility_id,contract_version,checksum,"
                + " content_digest,object_key,received_at,minimum_observed_at,maximum_observed_at,"
                + " observation_count) VALUES (?,?,?,?,?,?,?,?,?,?,?,1)")) {
      Instant time = Instant.parse(observedAt);
      statement.setObject(1, batchId);
      statement.setString(2, "edge-gateway-01");
      statement.setString(3, "1.0.0");
      statement.setString(4, "facility-alpha");
      statement.setString(5, "raw-observation.batch.v1");
      statement.setString(6, "sha256:" + batchId.toString().replace("-", ""));
      statement.setString(7, "sha-256=:test:");
      statement.setString(8, "raw-observations/" + batchId + ".json.gz");
      statement.setObject(9, java.sql.Timestamp.from(time));
      statement.setObject(10, java.sql.Timestamp.from(time));
      statement.setObject(11, java.sql.Timestamp.from(time));
      statement.executeUpdate();
    }
  }
}
