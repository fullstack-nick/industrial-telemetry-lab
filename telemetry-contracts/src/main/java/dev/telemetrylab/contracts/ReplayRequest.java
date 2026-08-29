package dev.telemetrylab.contracts;

import java.time.Instant;

public record ReplayRequest(
    String facilityId,
    Instant from,
    Instant to,
    String mappingVersion,
    String qualityRulesVersion,
    String reason) {}
