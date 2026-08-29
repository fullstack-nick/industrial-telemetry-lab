package dev.telemetrylab.contracts;

import java.time.Instant;
import java.util.List;

public record RawObservationBatch(
    String contractVersion,
    String batchId,
    String collectorId,
    String collectorVersion,
    String facilityId,
    Instant createdAt,
    List<RawObservation> observations) {
  public RawObservationBatch {
    observations = observations == null ? List.of() : List.copyOf(observations);
  }
}
