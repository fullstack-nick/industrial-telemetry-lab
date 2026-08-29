package dev.telemetrylab.contracts;

public record CursorGone(String sourceEpoch, long earliestSequence, long latestSequence) {}
