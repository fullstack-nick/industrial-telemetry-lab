package dev.telemetrylab.collector;

record CollectorState(
    String sourceEpoch,
    long sourceCursor,
    boolean gapDetected,
    String gapDetail,
    boolean pollingPaused) {}
