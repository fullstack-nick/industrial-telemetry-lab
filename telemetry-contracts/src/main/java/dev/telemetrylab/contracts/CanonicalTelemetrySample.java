package dev.telemetrylab.contracts;

import java.time.Instant;
import java.util.List;

public record CanonicalTelemetrySample(
    String contractVersion,
    String observationId,
    String facilityId,
    String assetId,
    String signalId,
    Instant observedAt,
    Instant receivedAt,
    Instant processedAt,
    double value,
    String unit,
    Quality quality,
    List<String> flags,
    CanonicalSource source) {
  public CanonicalTelemetrySample {
    flags = flags == null ? List.of() : List.copyOf(flags);
  }
}
