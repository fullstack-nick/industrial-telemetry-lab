package dev.telemetrylab.simulator;

public record FaultPatch(
    Double duplicateRate,
    Double outOfOrderRate,
    Double invalidUnitRate,
    Double badQualityRate,
    Double futureTimestampRate,
    Long responseDelayMs,
    Boolean connectionAvailable,
    Boolean newUnknownTagEnabled) {
  FaultSettings applyTo(FaultSettings current) {
    return new FaultSettings(
        value(duplicateRate, current.duplicateRate()),
        value(outOfOrderRate, current.outOfOrderRate()),
        value(invalidUnitRate, current.invalidUnitRate()),
        value(badQualityRate, current.badQualityRate()),
        value(futureTimestampRate, current.futureTimestampRate()),
        value(responseDelayMs, current.responseDelayMs()),
        value(connectionAvailable, current.connectionAvailable()),
        value(newUnknownTagEnabled, current.newUnknownTagEnabled()));
  }

  private static double value(Double candidate, double fallback) {
    return candidate == null ? fallback : candidate;
  }

  private static long value(Long candidate, long fallback) {
    return candidate == null ? fallback : candidate;
  }

  private static boolean value(Boolean candidate, boolean fallback) {
    return candidate == null ? fallback : candidate;
  }
}
