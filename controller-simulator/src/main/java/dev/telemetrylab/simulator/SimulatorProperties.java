package dev.telemetrylab.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telemetry.simulator")
public record SimulatorProperties(
    long seed,
    int historyCapacity,
    long productionIntervalMs,
    String localToken,
    String sourceSystem) {}
