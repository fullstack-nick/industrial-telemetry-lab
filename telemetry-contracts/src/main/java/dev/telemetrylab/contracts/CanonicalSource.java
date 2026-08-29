package dev.telemetrylab.contracts;

public record CanonicalSource(
    String sourceSystem,
    String sourceEpoch,
    long sourceSequence,
    String sourceTag,
    String collectorId,
    String collectorVersion,
    String mappingVersion,
    String qualityRulesVersion) {}
