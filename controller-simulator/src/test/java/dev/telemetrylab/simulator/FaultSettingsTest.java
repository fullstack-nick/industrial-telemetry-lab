package dev.telemetrylab.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

  @Test
  void outOfOrderFaultCreatesLateEventTimestampsWithoutBreakingTheCursor() {
    Instant now = Instant.parse("2026-08-29T12:00:00Z");
    ControllerHistory history =
        new ControllerHistory(
            new SimulatorProperties(7, 1000, 5000, "token", "controller-a"),
            Clock.fixed(now, ZoneOffset.UTC),
            new SimpleMeterRegistry());
    history.updateFaults(new FaultPatch(null, 1.0, null, null, null, null, null, null));

    history.generateReadings();
    var page = history.read(null, 0, 500);

    assertThat(page.readings()).hasSize(36);
    assertThat(page.nextSequence()).isEqualTo(36);
    assertThat(page.readings())
        .allSatisfy(reading -> assertThat(reading.observedAt()).isEqualTo(now.minusSeconds(600)));
  }

  @Test
  void expiredCursorReturnsTheRetainedSequenceBoundaryWithoutSkipping() {
    ControllerHistory history =
        new ControllerHistory(
            new SimulatorProperties(7, 40, 5000, "token", "controller-a"),
            Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC),
            new SimpleMeterRegistry());
    history.generateReadings();
    String epoch = history.read(null, 0, 1).sourceEpoch();
    history.generateReadings();

    assertThatThrownBy(() -> history.read(epoch, 1, 500))
        .isInstanceOf(CursorExpiredException.class)
        .satisfies(
            error -> {
              CursorExpiredException expired = (CursorExpiredException) error;
              assertThat(expired.detail().sourceEpoch()).isEqualTo(epoch);
              assertThat(expired.detail().earliestSequence()).isGreaterThan(2);
              assertThat(expired.detail().latestSequence()).isEqualTo(72);
            });
  }
}
