package dev.telemetrylab.contracts;

import java.util.List;

public record ControllerReadingsPage(
    String sourceEpoch, long nextSequence, List<ControllerReading> readings) {
  public ControllerReadingsPage {
    readings = readings == null ? List.of() : List.copyOf(readings);
  }
}
