package dev.telemetrylab.contracts;

import java.time.Instant;

public record ControllerReading(
    long sequence, String tag, Instant observedAt, double value, String unit, int qualityCode) {}
