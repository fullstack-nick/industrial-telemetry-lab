package dev.telemetrylab.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {
  @Test
  void exponentialBackoffIsBoundedAndJittered() {
    RetryPolicy policy =
        new RetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(2), new Random(42));

    Duration first = policy.nextDelay(1);
    Duration late = policy.nextDelay(20);

    assertThat(first).isBetween(Duration.ofMillis(500), Duration.ofSeconds(1));
    assertThat(late).isBetween(Duration.ofSeconds(60), Duration.ofMinutes(2));
  }
}
