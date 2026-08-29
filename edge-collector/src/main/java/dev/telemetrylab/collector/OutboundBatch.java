package dev.telemetrylab.collector;

import java.time.Instant;

record OutboundBatch(
    String batchId,
    byte[] compressedPayload,
    String contentDigest,
    String checksum,
    int observationCount,
    int attemptCount,
    Instant createdAt) {}
