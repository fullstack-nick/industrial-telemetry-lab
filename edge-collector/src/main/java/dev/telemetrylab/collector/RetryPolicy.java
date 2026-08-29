package dev.telemetrylab.collector;

import java.time.Duration;
import java.util.random.RandomGenerator;

public final class RetryPolicy {
  private final Duration initial;
  private final Duration maximum;
  private final RandomGenerator random;

  public RetryPolicy(Duration initial, Duration maximum, RandomGenerator random) {
    this.initial = initial;
    this.maximum = maximum;
    this.random = random;
  }

  public Duration nextDelay(int attempt) {
    int safeAttempt = Math.max(1, Math.min(attempt, 30));
    long multiplier = 1L << Math.min(safeAttempt - 1, 20);
    long ceiling = Math.min(maximum.toMillis(), Math.multiplyExact(initial.toMillis(), multiplier));
    long jittered = ceiling <= 1 ? ceiling : random.nextLong(ceiling / 2, ceiling + 1);
    return Duration.ofMillis(jittered);
  }
}
