package dev.telemetrylab.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record RawObservation(
    String sourceSystem,
    String sourceEpoch,
    long sourceSequence,
    String sourceTag,
    Instant observedAt,
    JsonNode rawValue,
    String rawUnit,
    int sourceQualityCode) {}
