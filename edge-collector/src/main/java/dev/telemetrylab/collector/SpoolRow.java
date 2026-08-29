package dev.telemetrylab.collector;

import java.time.Instant;

record SpoolRow(
    long id,
    String sourceSystem,
    String sourceEpoch,
    long sourceSequence,
    String sourceTag,
    Instant observedAt,
    Instant persistedAt,
    String rawValueJson,
    String rawUnit,
    int sourceQualityCode) {}
