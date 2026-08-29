package dev.telemetrylab.collector;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/collector/v1")
public class CollectorApi {
  private final CollectorPipeline pipeline;
  private final CollectorProperties properties;

  public CollectorApi(CollectorPipeline pipeline, CollectorProperties properties) {
    this.pipeline = pipeline;
    this.properties = properties;
  }

  @GetMapping("/status")
  Map<String, Object> status() {
    return pipeline.status();
  }

  @PostMapping("/gap/recover")
  Map<String, Object> recoverGap(
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestBody GapRecoveryRequest recovery) {
    requireToken(authorization);
    if (recovery.sourceEpoch() == null
        || recovery.sourceEpoch().isBlank()
        || recovery.cursor() < 0) {
      throw new IllegalArgumentException("sourceEpoch is required and cursor must be non-negative");
    }
    pipeline.recoverGap(recovery.sourceEpoch(), recovery.cursor());
    return Map.of(
        "recovered", true, "sourceEpoch", recovery.sourceEpoch(), "cursor", recovery.cursor());
  }

  @ExceptionHandler({IllegalArgumentException.class, UnauthorizedException.class})
  ProblemDetail problem(RuntimeException exception, HttpServletRequest request) {
    boolean unauthorized = exception instanceof UnauthorizedException;
    HttpStatus status = unauthorized ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
    String reason = unauthorized ? "UNAUTHORIZED" : "INVALID_REQUEST";
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
    problem.setTitle(unauthorized ? "Unauthorized" : "Invalid request");
    problem.setType(URI.create("urn:telemetry-lab:problem:" + reason.toLowerCase()));
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("reasonCode", reason);
    return problem;
  }

  private void requireToken(String authorization) {
    if (!("Bearer " + properties.localToken()).equals(authorization)) {
      throw new UnauthorizedException();
    }
  }

  record GapRecoveryRequest(String sourceEpoch, long cursor) {}

  private static final class UnauthorizedException extends RuntimeException {
    UnauthorizedException() {
      super("A valid local bearer token is required");
    }
  }
}
