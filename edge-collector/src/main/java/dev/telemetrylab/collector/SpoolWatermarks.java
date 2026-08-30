package dev.telemetrylab.collector;

record SpoolWatermarks(long maximumRows, int highPercent, int lowPercent) {
  SpoolWatermarks {
    if (maximumRows < 1 || lowPercent < 0 || highPercent > 100 || lowPercent >= highPercent) {
      throw new IllegalArgumentException("spool watermarks are invalid");
    }
  }

  long highRows() {
    return Math.round(maximumRows * (highPercent / 100.0));
  }

  long lowRows() {
    return Math.round(maximumRows * (lowPercent / 100.0));
  }

  boolean remainPaused(long currentRows) {
    return currentRows > lowRows();
  }

  boolean shouldPause(long currentRows, int nextPageMaximum) {
    return currentRows >= highRows() || currentRows + nextPageMaximum > maximumRows;
  }
}
