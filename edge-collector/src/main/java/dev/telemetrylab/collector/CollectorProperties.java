package dev.telemetrylab.collector;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telemetry.collector")
public record CollectorProperties(
    String collectorId,
    String collectorVersion,
    String configurationVersion,
    String sourceAdapterVersion,
    String facilityId,
    String sourceSystem,
    String sourceUrl,
    String gatewayUrl,
    String localToken,
    int pollLimit,
    int batchSize,
    Duration batchMaximumAge,
    int spoolMaxRows,
    int highWatermarkPercent,
    int lowWatermarkPercent,
    Duration acknowledgedRetention) {
  public CollectorProperties {
    if (pollLimit < 1 || pollLimit > 500 || batchSize < 1 || batchSize > 500) {
      throw new IllegalArgumentException("pollLimit and batchSize must be between 1 and 500");
    }
    if (lowWatermarkPercent < 0
        || highWatermarkPercent > 100
        || lowWatermarkPercent >= highWatermarkPercent) {
      throw new IllegalArgumentException("spool watermarks are invalid");
    }
  }
}
