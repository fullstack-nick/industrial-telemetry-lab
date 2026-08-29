package dev.telemetrylab.simulator;

import dev.telemetrylab.contracts.CursorGone;

final class CursorExpiredException extends RuntimeException {
  private final CursorGone detail;

  CursorExpiredException(CursorGone detail) {
    super("The requested source cursor is older than retained simulator history");
    this.detail = detail;
  }

  CursorGone detail() {
    return detail;
  }
}
