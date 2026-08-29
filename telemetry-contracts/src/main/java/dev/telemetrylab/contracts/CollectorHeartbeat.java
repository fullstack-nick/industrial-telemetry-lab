package dev.telemetrylab.contracts;

import java.time.Instant;

public record CollectorHeartbeat(
    String collectorVersion,
    String configurationVersion,
    String sourceAdapterVersion,
    long spoolObservationCount,
    long oldestUnsentObservationAgeSeconds,
    Instant lastSuccessfulUploadAt,
    boolean sourceConnected) {}
