package dev.telemetrylab.platform.worker;

import dev.telemetrylab.contracts.ProcessingOutcome;
import dev.telemetrylab.contracts.Quality;
import dev.telemetrylab.contracts.RawObservation;
import dev.telemetrylab.contracts.ReasonCode;
import dev.telemetrylab.platform.worker.MappingCatalog.MappingResult;
import dev.telemetrylab.platform.worker.MappingCatalog.UnknownSourceTagException;
import dev.telemetrylab.platform.worker.QualityCatalog.QualityRules;
import dev.telemetrylab.platform.worker.QualityCatalog.ValueRange;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ObservationEvaluator {
  private final MappingCatalog mappings;
  private final QualityCatalog qualityCatalog;

  public ObservationEvaluator(MappingCatalog mappings, QualityCatalog qualityCatalog) {
    this.mappings = mappings;
    this.qualityCatalog = qualityCatalog;
  }

  public ObservationDecision evaluate(
      RawObservation observation,
      String mappingVersion,
      String qualityRulesVersion,
      Instant receivedAt,
      Instant processedAt) {
    if (observation.sourceTag() == null || observation.sourceTag().isBlank()) {
      return ObservationDecision.rejected(
          ReasonCode.MISSING_SOURCE_TAG, "The source tag is missing");
    }

    MappingResult mapping;
    try {
      mapping = mappings.map(observation.sourceTag(), mappingVersion);
    } catch (UnknownSourceTagException exception) {
      return ObservationDecision.rejected(
          ReasonCode.UNKNOWN_SOURCE_TAG, "No mapping rule recognizes the source tag");
    }
    if (observation.rawValue() == null || !observation.rawValue().isNumber()) {
      return ObservationDecision.rejected(
          ReasonCode.NON_NUMERIC_VALUE, "The raw value is not numeric");
    }
    double value = observation.rawValue().doubleValue();
    if (!Double.isFinite(value)) {
      return ObservationDecision.rejected(
          ReasonCode.NON_FINITE_VALUE, "The raw value is NaN or infinite");
    }
    if (!mapping.expectedSourceUnit().equals(observation.rawUnit())) {
      return ObservationDecision.rejected(
          ReasonCode.UNSUPPORTED_RAW_UNIT,
          "Expected source unit "
              + mapping.expectedSourceUnit()
              + " but received "
              + observation.rawUnit());
    }

    QualityRules rules = qualityCatalog.get(qualityRulesVersion);
    ValueRange range = rules.ranges().get(mapping.signalId());
    if (range == null || value < range.minimum() || value > range.maximum()) {
      return ObservationDecision.rejected(
          ReasonCode.VALUE_OUT_OF_RANGE, "The raw value is outside the configured physical range");
    }
    long futureSeconds = Duration.between(processedAt, observation.observedAt()).toSeconds();
    if (futureSeconds > rules.futureTimestampRejectSeconds()) {
      return ObservationDecision.rejected(
          ReasonCode.TIMESTAMP_TOO_FAR_IN_FUTURE, "The source timestamp is too far in the future");
    }

    List<String> flags = new ArrayList<>();
    if (futureSeconds > rules.futureTimestampFlagSeconds()) {
      flags.add("FUTURE_TIMESTAMP");
    }
    if (Duration.between(observation.observedAt(), receivedAt).toSeconds()
        > rules.lateArrivalFlagSeconds()) {
      flags.add("LATE_ARRIVAL");
    }
    Quality quality =
        observation.sourceQualityCode() == rules.goodSourceQualityCode()
            ? Quality.GOOD
            : Quality.BAD;
    ProcessingOutcome outcome =
        flags.isEmpty() ? ProcessingOutcome.ACCEPTED : ProcessingOutcome.FLAGGED;
    return ObservationDecision.accepted(mapping, value, quality, flags, outcome);
  }

  public record ObservationDecision(
      boolean accepted,
      ReasonCode reasonCode,
      String reason,
      MappingResult mapping,
      double value,
      Quality quality,
      List<String> flags,
      ProcessingOutcome outcome) {
    public ObservationDecision {
      flags = List.copyOf(flags);
    }

    static ObservationDecision rejected(ReasonCode reasonCode, String reason) {
      return new ObservationDecision(
          false, reasonCode, reason, null, Double.NaN, null, List.of(), ProcessingOutcome.REJECTED);
    }

    static ObservationDecision accepted(
        MappingResult mapping,
        double value,
        Quality quality,
        List<String> flags,
        ProcessingOutcome outcome) {
      return new ObservationDecision(true, null, null, mapping, value, quality, flags, outcome);
    }

    ObservationDecision withFlag(String flag) {
      ArrayList<String> updated = new ArrayList<>(flags);
      if (!updated.contains(flag)) {
        updated.add(flag);
      }
      return new ObservationDecision(
          accepted,
          reasonCode,
          reason,
          mapping,
          value,
          quality,
          updated,
          ProcessingOutcome.FLAGGED);
    }
  }
}
