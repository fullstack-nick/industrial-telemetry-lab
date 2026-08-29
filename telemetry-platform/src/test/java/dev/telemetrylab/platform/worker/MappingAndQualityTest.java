package dev.telemetrylab.platform.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.DoubleNode;
import dev.telemetrylab.contracts.ProcessingOutcome;
import dev.telemetrylab.contracts.RawObservation;
import dev.telemetrylab.contracts.ReasonCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MappingAndQualityTest {
  private final MappingCatalog mappings = new MappingCatalog();
  private final QualityCatalog quality = new QualityCatalog();
  private final ObservationEvaluator evaluator = new ObservationEvaluator(mappings, quality);

  @Test
  void mapsTheThreePlannedSignals() {
    assertThat(mappings.map("CTRL_A.ZONE[07].TEMP_PV", "controller-a-mapping-1.0.0").assetId())
        .isEqualTo("zone-07");
    assertThat(mappings.map("CTRL_A.ZONE[07].RH_PV", "controller-a-mapping-1.0.0").signalId())
        .isEqualTo("environment.relative_humidity");
    assertThat(mappings.map("CTRL_A.ZONE[07].FAN_OUT", "controller-a-mapping-1.0.0").signalId())
        .isEqualTo("ventilation.output");
  }

  @Test
  void mappingVersionOneOneResolvesPreviouslyUnknownTag() {
    assertThatThrownBy(
            () -> mappings.map("CTRL_A.ZONE[07].TEMP_AUX_PV", "controller-a-mapping-1.0.0"))
        .isInstanceOf(MappingCatalog.UnknownSourceTagException.class);

    assertThat(mappings.map("CTRL_A.ZONE[07].TEMP_AUX_PV", "controller-a-mapping-1.1.0").signalId())
        .isEqualTo("environment.air_temperature");
  }

  @Test
  void invalidUnitIsRejectedPrecisely() {
    RawObservation observation = observation("invalid-unit", Instant.parse("2026-08-29T10:00:00Z"));

    var decision =
        evaluator.evaluate(
            observation,
            "controller-a-mapping-1.0.0",
            "quality-rules-1.0.0",
            Instant.parse("2026-08-29T10:00:01Z"),
            Instant.parse("2026-08-29T10:00:02Z"));

    assertThat(decision.accepted()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(ReasonCode.UNSUPPORTED_RAW_UNIT);
  }

  @Test
  void futureTimestampIsFlaggedButRemainsQueryable() {
    RawObservation observation = observation("degC", Instant.parse("2026-08-29T10:00:20Z"));

    var decision =
        evaluator.evaluate(
            observation,
            "controller-a-mapping-1.0.0",
            "quality-rules-1.0.0",
            Instant.parse("2026-08-29T10:00:01Z"),
            Instant.parse("2026-08-29T10:00:02Z"));

    assertThat(decision.accepted()).isTrue();
    assertThat(decision.outcome()).isEqualTo(ProcessingOutcome.FLAGGED);
    assertThat(decision.flags()).contains("FUTURE_TIMESTAMP");
  }

  private static RawObservation observation(String unit, Instant observedAt) {
    return new RawObservation(
        "controller-a",
        "47af63a8-85c4-4a34-9b60-f63d6e16f564",
        42,
        "CTRL_A.ZONE[07].TEMP_PV",
        observedAt,
        DoubleNode.valueOf(24.5),
        unit,
        192);
  }
}
