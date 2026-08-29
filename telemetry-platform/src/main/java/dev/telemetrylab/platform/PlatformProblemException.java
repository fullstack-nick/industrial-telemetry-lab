package dev.telemetrylab.platform;

import org.springframework.http.HttpStatus;

public final class PlatformProblemException extends RuntimeException {
  private final HttpStatus status;
  private final String reasonCode;
  private final Integer retryAfterSeconds;

  public PlatformProblemException(HttpStatus status, String reasonCode, String detail) {
    this(status, reasonCode, detail, null);
  }

  public PlatformProblemException(
      HttpStatus status, String reasonCode, String detail, Integer retryAfterSeconds) {
    super(detail);
    this.status = status;
    this.reasonCode = reasonCode;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  HttpStatus status() {
    return status;
  }

  String reasonCode() {
    return reasonCode;
  }

  Integer retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
