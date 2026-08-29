package dev.telemetrylab.simulator;

public record FaultSettings(
    double duplicateRate,
    double outOfOrderRate,
    double invalidUnitRate,
    double badQualityRate,
    double futureTimestampRate,
    long responseDelayMs,
    boolean connectionAvailable,
    boolean newUnknownTagEnabled) {
  public static FaultSettings healthy() {
    return new FaultSettings(0, 0, 0, 0, 0, 0, true, false);
  }

  public FaultSettings {
    validateRate("duplicateRate", duplicateRate);
    validateRate("outOfOrderRate", outOfOrderRate);
    validateRate("invalidUnitRate", invalidUnitRate);
    validateRate("badQualityRate", badQualityRate);
    validateRate("futureTimestampRate", futureTimestampRate);
    if (responseDelayMs < 0 || responseDelayMs > 30_000) {
      throw new IllegalArgumentException("responseDelayMs must be between 0 and 30000");
    }
  }

  private static void validateRate(String name, double rate) {
    if (!Double.isFinite(rate) || rate < 0 || rate > 1) {
      throw new IllegalArgumentException(name + " must be between 0 and 1");
    }
  }
}
