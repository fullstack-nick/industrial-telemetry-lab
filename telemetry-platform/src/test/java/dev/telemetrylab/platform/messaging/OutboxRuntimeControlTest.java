package dev.telemetrylab.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.telemetrylab.platform.PlatformProperties;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxRuntimeControlTest {
  @Test
  void targetedGapIsConsumedOnlyByItsBatchAndOnlyOnce() {
    OutboxRuntimeControl control = new OutboxRuntimeControl(properties(true));
    UUID target = UUID.randomUUID();

    assertThat(control.armConfirmCommitGap(true, target)).isTrue();
    assertThat(control.consumeConfirmCommitGap(UUID.randomUUID())).isFalse();
    assertThat(control.consumeConfirmCommitGap(target)).isTrue();
    assertThat(control.consumeConfirmCommitGap(target)).isFalse();
  }

  @Test
  void failureInjectionCannotBeArmedWhenDisabled() {
    OutboxRuntimeControl control = new OutboxRuntimeControl(properties(false));

    assertThatThrownBy(() -> control.armConfirmCommitGap(true, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Local failure injection is disabled");
  }

  private static PlatformProperties properties(boolean failureInjectionEnabled) {
    return new PlatformProperties(
        "gateway",
        "token",
        "http://raw-store",
        "access",
        "secret",
        "bucket",
        1_048_576,
        10_485_760,
        500,
        "1.0",
        "1.0",
        Duration.ofDays(7),
        100,
        500,
        Duration.ofHours(24),
        failureInjectionEnabled);
  }
}
