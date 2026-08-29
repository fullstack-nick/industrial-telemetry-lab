package dev.telemetrylab.platform.gateway;

import dev.telemetrylab.contracts.RawObservationBatch;
import java.time.Instant;

record ValidatedBatch(
    RawObservationBatch contract,
    byte[] compressedBytes,
    String contentDigest,
    String checksum,
    Instant receivedAt,
    String traceParent) {}
