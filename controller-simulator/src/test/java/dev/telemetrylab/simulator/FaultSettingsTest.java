package dev.telemetrylab.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FaultSettingsTest {
  @Test
  void patchPreservesUnspecifiedValues() {
    FaultSettings result =
        new FaultPatch(0.1, null, null, null, null, 250L, null, true)
            .applyTo(FaultSettings.healthy());

    assertThat(result.duplicateRate()).isEqualTo(0.1);
    assertThat(result.responseDelayMs()).isEqualTo(250);
    assertThat(result.connectionAvailable()).isTrue();
    assertThat(result.newUnknownTagEnabled()).isTrue();
  }

  @Test
  void rejectsOutOfRangeRates() {
    assertThatThrownBy(() -> new FaultSettings(1.1, 0, 0, 0, 0, 0, true, false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
