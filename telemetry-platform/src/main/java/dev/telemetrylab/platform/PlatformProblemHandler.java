package dev.telemetrylab.platform;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
class PlatformProblemHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(PlatformProblemHandler.class);

  @ExceptionHandler(PlatformProblemException.class)
  ResponseEntity<ProblemDetail> platformProblem(
      PlatformProblemException exception, HttpServletRequest request) {
    ProblemDetail problem =
        detail(exception.status(), exception.reasonCode(), exception.getMessage(), request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.status());
    if (exception.retryAfterSeconds() != null) {
      response.header(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds().toString());
    }
    return response.body(problem);
  }

  @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
  ProblemDetail invalidRequest(Exception exception, HttpServletRequest request) {
    return detail(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ProblemDetail resourceNotFound(NoResourceFoundException exception, HttpServletRequest request) {
    return detail(
        HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "No API resource exists at this path", request);
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception exception, HttpServletRequest request) {
    String errorId = UUID.randomUUID().toString();
    LOGGER.error("Unexpected request failure; errorId={}", errorId, exception);
    ProblemDetail problem =
        detail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "The request could not be completed; errorId=" + errorId,
            request);
    return problem;
  }

  private static ProblemDetail detail(
      HttpStatus status, String reasonCode, String message, HttpServletRequest request) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            status, message == null ? status.getReasonPhrase() : message);
    problem.setTitle(status.getReasonPhrase());
    problem.setType(
        URI.create("urn:telemetry-lab:problem:" + reasonCode.toLowerCase().replace('_', '-')));
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("reasonCode", reasonCode);
    return problem;
  }
}
