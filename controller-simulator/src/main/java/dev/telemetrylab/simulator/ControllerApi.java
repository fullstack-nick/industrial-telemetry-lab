package dev.telemetrylab.simulator;

import dev.telemetrylab.contracts.ControllerReadingsPage;
import dev.telemetrylab.contracts.CursorGone;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/controller/v1")
public class ControllerApi {
  private final ControllerHistory history;
  private final SimulatorProperties properties;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The mutable history is an application-scoped Spring collaborator, not caller-owned data")
  public ControllerApi(ControllerHistory history, SimulatorProperties properties) {
    this.history = history;
    this.properties = properties;
  }

  @GetMapping("/readings")
  ControllerReadingsPage readings(
      @RequestParam(defaultValue = "0") long afterSequence,
      @RequestParam(required = false) String sourceEpoch,
      @RequestParam(defaultValue = "500") int limit) {
    if (afterSequence < 0 || limit < 1 || limit > 500) {
      throw new IllegalArgumentException(
          "afterSequence must be non-negative and limit must be 1..500");
    }
    return history.read(sourceEpoch, afterSequence, limit);
  }

  @GetMapping("/faults")
  FaultSettings faults() {
    return history.faults();
  }

  @PutMapping("/faults")
  FaultSettings updateFaults(
      @RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestBody FaultPatch patch) {
    requireToken(authorization);
    return history.updateFaults(patch);
  }

  @PostMapping("/source/restart")
  Map<String, String> restart(
      @RequestHeader(name = "Authorization", required = false) String authorization) {
    requireToken(authorization);
    return Map.of("sourceEpoch", history.restartSource());
  }

  @ExceptionHandler(CursorExpiredException.class)
  ResponseEntity<CursorGone> cursorExpired(CursorExpiredException exception) {
    return ResponseEntity.status(HttpStatus.GONE).body(exception.detail());
  }

  @ExceptionHandler(ControllerHistory.SourceUnavailableException.class)
  ProblemDetail sourceUnavailable(HttpServletRequest request) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE, "The simulated controller connection is unavailable");
    problem.setType(URI.create("urn:telemetry-lab:problem:source-unavailable"));
    problem.setTitle("Source unavailable");
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("reasonCode", "SOURCE_UNAVAILABLE");
    return problem;
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail badRequest(IllegalArgumentException exception, HttpServletRequest request) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    problem.setType(URI.create("urn:telemetry-lab:problem:invalid-request"));
    problem.setTitle("Invalid request");
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("reasonCode", "INVALID_REQUEST");
    return problem;
  }

  private void requireToken(String authorization) {
    if (!("Bearer " + properties.localToken()).equals(authorization)) {
      throw new UnauthorizedException();
    }
  }

  @ExceptionHandler(UnauthorizedException.class)
  ProblemDetail unauthorized(HttpServletRequest request) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED, "A valid local bearer token is required");
    problem.setType(URI.create("urn:telemetry-lab:problem:unauthorized"));
    problem.setTitle("Unauthorized");
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("reasonCode", "UNAUTHORIZED");
    return problem;
  }

  private static final class UnauthorizedException extends RuntimeException {}
}
