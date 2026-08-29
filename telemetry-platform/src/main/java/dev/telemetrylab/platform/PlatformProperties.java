package dev.telemetrylab.platform;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telemetry.platform")
public record PlatformProperties(
    String mode,
    String localToken,
    String rawStoreEndpoint,
    String rawStoreAccessKey,
    String rawStoreSecretKey,
    String rawStoreBucket,
    int maximumCompressedBytes,
    int maximumDecompressedBytes,
    int maximumObservations,
    String defaultMappingVersion,
    String defaultQualityRulesVersion,
    Duration maximumQueryRange,
    int defaultPageSize,
    int maximumPageSize,
    Duration maximumReplayRange,
    boolean failureInjectionEnabled) {}
