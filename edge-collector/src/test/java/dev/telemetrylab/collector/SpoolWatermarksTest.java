package dev.telemetrylab.collector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpoolWatermarksTest {
  private final SpoolWatermarks watermarks = new SpoolWatermarks(100_000, 80, 60);

  @Test
  void pausesAtHighWaterAndBeforeTheNextPageWouldOverflow() {
    assertThat(watermarks.shouldPause(79_499, 500)).isFalse();
    assertThat(watermarks.shouldPause(80_000, 500)).isTrue();
    assertThat(watermarks.shouldPause(99_750, 500)).isTrue();
  }

  @Test
  void resumesOnlyAtOrBelowLowWater() {
    assertThat(watermarks.remainPaused(60_001)).isTrue();
    assertThat(watermarks.remainPaused(60_000)).isFalse();
  }
}
