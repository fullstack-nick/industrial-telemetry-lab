package dev.telemetrylab.platform.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.telemetrylab.contracts.CanonicalSource;
import dev.telemetrylab.contracts.CanonicalTelemetrySample;
import dev.telemetrylab.contracts.ContractVersions;
import dev.telemetrylab.contracts.Quality;
import dev.telemetrylab.platform.PlatformProblemException;
import dev.telemetrylab.platform.PlatformProperties;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class TelemetryQueryService {
  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final PlatformProperties properties;

  TelemetryQueryService(
      NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, PlatformProperties properties) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  TelemetryPage query(
      String facilityId,
      String assetId,
      String signalId,
      Instant from,
      Instant to,
      Boolean includeFlagged,
      Boolean includeBadQuality,
      Integer requestedPageSize,
      String cursor) {
    validateRange(from, to);
    int pageSize = requestedPageSize == null ? properties.defaultPageSize() : requestedPageSize;
    if (pageSize < 1 || pageSize > properties.maximumPageSize()) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST,
          "INVALID_PAGE_SIZE",
          "pageSize must be between 1 and " + properties.maximumPageSize());
    }

    StringBuilder sql =
        new StringBuilder(
            """
            SELECT observation_id, facility_id, asset_id, signal_id, observed_at, received_at,
                   processed_at, value_double, unit, quality, flags::text AS flags,
                   source_system, source_sequence, source_epoch, source_tag, collector_id,
                   collector_version, mapping_version, quality_rules_version
            FROM telemetry_sample
            WHERE facility_id=:facilityId AND observed_at >= :from AND observed_at < :to
            """);
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("facilityId", facilityId)
            .addValue("from", Timestamp.from(from))
            .addValue("to", Timestamp.from(to));
    if (assetId != null && !assetId.isBlank()) {
      sql.append(" AND asset_id=:assetId");
      parameters.addValue("assetId", assetId);
    }
    if (signalId != null && !signalId.isBlank()) {
      sql.append(" AND signal_id=:signalId");
      parameters.addValue("signalId", signalId);
    }
    if (Boolean.FALSE.equals(includeFlagged)) {
      sql.append(" AND flags='[]'::jsonb");
    }
    if (Boolean.FALSE.equals(includeBadQuality)) {
      sql.append(" AND quality='GOOD'");
    }
    if (cursor != null && !cursor.isBlank()) {
      Cursor decoded = decodeCursor(cursor);
      sql.append(" AND (observed_at, observation_id) > (:cursorTime, :cursorId)");
      parameters
          .addValue("cursorTime", Timestamp.from(decoded.observedAt()))
          .addValue("cursorId", decoded.observationId());
    }
    sql.append(" ORDER BY observed_at, observation_id LIMIT :limit");
    parameters.addValue("limit", pageSize + 1);

    List<CanonicalTelemetrySample> rows =
        jdbc.query(sql.toString(), parameters, (result, row) -> sample(result));
    boolean hasMore = rows.size() > pageSize;
    List<CanonicalTelemetrySample> items =
        hasMore ? List.copyOf(rows.subList(0, pageSize)) : List.copyOf(rows);
    String next =
        hasMore
            ? encodeCursor(items.getLast().observedAt(), items.getLast().observationId())
            : null;
    return new TelemetryPage(items, next);
  }

  private CanonicalTelemetrySample sample(java.sql.ResultSet result) throws java.sql.SQLException {
    List<String> flags;
    try {
      flags = objectMapper.readValue(result.getString("flags"), new TypeReference<>() {});
    } catch (JsonProcessingException exception) {
      throw new java.sql.SQLException("Invalid flags JSON in telemetry_sample", exception);
    }
    CanonicalSource source =
        new CanonicalSource(
            result.getString("source_system"),
            result.getString("source_epoch"),
            result.getLong("source_sequence"),
            result.getString("source_tag"),
            result.getString("collector_id"),
            result.getString("collector_version"),
            result.getString("mapping_version"),
            result.getString("quality_rules_version"));
    return new CanonicalTelemetrySample(
        ContractVersions.TELEMETRY_SAMPLE_V1,
        result.getString("observation_id"),
        result.getString("facility_id"),
        result.getString("asset_id"),
        result.getString("signal_id"),
        result.getTimestamp("observed_at").toInstant(),
        result.getTimestamp("received_at").toInstant(),
        result.getTimestamp("processed_at").toInstant(),
        result.getDouble("value_double"),
        result.getString("unit"),
        Quality.valueOf(result.getString("quality")),
        flags,
        source);
  }

  private void validateRange(Instant from, Instant to) {
    if (from == null || to == null || !to.isAfter(from)) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "from must be before to");
    }
    if (Duration.between(from, to).compareTo(properties.maximumQueryRange()) > 0) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST,
          "QUERY_RANGE_TOO_LARGE",
          "Telemetry queries are limited to " + properties.maximumQueryRange());
    }
  }

  private static String encodeCursor(Instant observedAt, String observationId) {
    String raw = observedAt + "\u001f" + observationId;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private static Cursor decodeCursor(String cursor) {
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = decoded.split("\u001f", 2);
      if (parts.length != 2) {
        throw new IllegalArgumentException();
      }
      return new Cursor(Instant.parse(parts[0]), parts[1]);
    } catch (RuntimeException exception) {
      throw new PlatformProblemException(
          HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "The pagination cursor is invalid");
    }
  }

  record TelemetryPage(List<CanonicalTelemetrySample> items, String nextCursor) {}

  private record Cursor(Instant observedAt, String observationId) {}
}
