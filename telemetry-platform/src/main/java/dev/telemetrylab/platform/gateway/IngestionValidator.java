package dev.telemetrylab.platform.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.telemetrylab.contracts.ContentDigest;
import dev.telemetrylab.contracts.ContractVersions;
import dev.telemetrylab.contracts.GzipCodec;
import dev.telemetrylab.contracts.RawObservation;
import dev.telemetrylab.contracts.RawObservationBatch;
import dev.telemetrylab.platform.PlatformProblemException;
import dev.telemetrylab.platform.PlatformProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class IngestionValidator {
  private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  private final PlatformProperties properties;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  IngestionValidator(PlatformProperties properties, ObjectMapper objectMapper, Clock clock) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  ValidatedBatch validate(HttpServletRequest request) {
    long declaredLength = request.getContentLengthLong();
    if (declaredLength > properties.maximumCompressedBytes()) {
      throw problem(
          HttpStatus.CONTENT_TOO_LARGE,
          "COMPRESSED_PAYLOAD_TOO_LARGE",
          "Compressed request exceeds the 1 MiB limit");
    }
    if (!"gzip".equalsIgnoreCase(request.getHeader("Content-Encoding"))) {
      throw problem(HttpStatus.BAD_REQUEST, "GZIP_REQUIRED", "Content-Encoding must be gzip");
    }

    byte[] compressed;
    byte[] json;
    try {
      compressed =
          GzipCodec.readBounded(request.getInputStream(), properties.maximumCompressedBytes());
      if (!ContentDigest.headerMatches(request.getHeader("Content-Digest"), compressed)) {
        throw problem(
            HttpStatus.BAD_REQUEST,
            "CHECKSUM_MISMATCH",
            "Content-Digest does not match the exact compressed request bytes");
      }
      json = GzipCodec.decompress(compressed, properties.maximumDecompressedBytes());
    } catch (GzipCodec.PayloadLimitException exception) {
      throw problem(
          HttpStatus.CONTENT_TOO_LARGE,
          "PAYLOAD_TOO_LARGE",
          "Request exceeds a configured compressed or decompressed limit");
    } catch (IOException exception) {
      throw problem(HttpStatus.BAD_REQUEST, "INVALID_GZIP", "The gzip request body is invalid");
    }

    RawObservationBatch batch;
    try {
      batch = objectMapper.readValue(json, RawObservationBatch.class);
    } catch (IOException exception) {
      throw problem(HttpStatus.BAD_REQUEST, "INVALID_ENVELOPE", "The JSON envelope is invalid");
    }
    validateContract(batch);
    return new ValidatedBatch(
        batch,
        compressed,
        request.getHeader("Content-Digest"),
        ContentDigest.checksum(compressed),
        clock.instant(),
        request.getHeader("traceparent"));
  }

  private void validateContract(RawObservationBatch batch) {
    if (!ContractVersions.RAW_BATCH_V1.equals(batch.contractVersion())) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          "UNSUPPORTED_CONTRACT_VERSION",
          "Only raw-observation.batch.v1 is supported");
    }
    parseUuid(batch.batchId(), "batchId");
    requireIdentifier(batch.collectorId(), "collectorId");
    requireLength(batch.collectorVersion(), "collectorVersion", 64);
    requireIdentifier(batch.facilityId(), "facilityId");
    if (batch.createdAt() == null) {
      invalid("createdAt is required");
    }
    if (batch.observations().isEmpty()
        || batch.observations().size() > properties.maximumObservations()) {
      throw problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_OBSERVATION_COUNT",
          "observations must contain between 1 and " + properties.maximumObservations() + " items");
    }
    for (RawObservation observation : batch.observations()) {
      requireIdentifier(observation.sourceSystem(), "sourceSystem");
      parseUuid(observation.sourceEpoch(), "sourceEpoch");
      if (observation.sourceSequence() < 0) {
        invalid("sourceSequence must be non-negative");
      }
      requireLength(observation.sourceTag(), "sourceTag", 256);
      requireLength(observation.rawUnit(), "rawUnit", 32);
      if (observation.observedAt() == null || observation.rawValue() == null) {
        invalid("observedAt and rawValue are required");
      }
    }
  }

  private static void requireIdentifier(String value, String field) {
    requireLength(value, field, 64);
    if (!SAFE_IDENTIFIER.matcher(value).matches()) {
      invalid(field + " contains unsupported characters");
    }
  }

  private static void requireLength(String value, String field, int maximum) {
    if (value == null || value.isBlank() || value.length() > maximum) {
      invalid(field + " must contain 1.." + maximum + " characters");
    }
  }

  private static void parseUuid(String value, String field) {
    try {
      UUID.fromString(value);
    } catch (RuntimeException exception) {
      invalid(field + " must be a UUID");
    }
  }

  private static void invalid(String detail) {
    throw problem(HttpStatus.BAD_REQUEST, "INVALID_ENVELOPE", detail);
  }

  private static PlatformProblemException problem(
      HttpStatus status, String reasonCode, String detail) {
    return new PlatformProblemException(status, reasonCode, detail);
  }
}
