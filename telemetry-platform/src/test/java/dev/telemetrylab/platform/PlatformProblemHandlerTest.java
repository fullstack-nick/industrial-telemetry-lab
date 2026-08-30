package dev.telemetrylab.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class PlatformProblemHandlerTest {
  private final PlatformProblemHandler handler = new PlatformProblemHandler();

  @Test
  void unknownApiPathProducesStableNotFoundProblem() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/not-a-route");

    var problem =
        handler.resourceNotFound(
            new NoResourceFoundException(HttpMethod.GET, "/api/v1/not-a-route", ""), request);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(problem.getTitle()).isEqualTo("Not Found");
    assertThat(problem.getDetail()).isEqualTo("No API resource exists at this path");
    assertThat(problem.getInstance()).hasToString("/api/v1/not-a-route");
    assertThat(problem.getProperties()).containsEntry("reasonCode", "RESOURCE_NOT_FOUND");
  }
}
