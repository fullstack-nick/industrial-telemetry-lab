package dev.telemetrylab.platform.gateway;

import dev.telemetrylab.platform.LocalAuthorization;
import dev.telemetrylab.platform.PlatformProblemException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ingestion")
@ConditionalOnProperty(
    name = "telemetry.platform.mode",
    havingValue = "gateway",
    matchIfMissing = true)
class IngestionApi {
  private final IngestionValidator validator;
  private final GatewayService gateway;
  private final LocalAuthorization authorization;
  private final GatewayRuntimeControl runtimeControl;

  IngestionApi(
      IngestionValidator validator,
      GatewayService gateway,
      LocalAuthorization authorization,
      GatewayRuntimeControl runtimeControl) {
    this.validator = validator;
    this.gateway = gateway;
    this.authorization = authorization;
    this.runtimeControl = runtimeControl;
  }

  @PostMapping("/batches")
  ResponseEntity<Map<String, Object>> ingest(
      HttpServletRequest request,
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String bearer,
      @RequestHeader(name = "X-Lab-Fail-After-Raw-Store", defaultValue = "false")
          boolean failAfterRawStore) {
    authorization.require(bearer);
    if (runtimeControl.overloaded()) {
      throw new PlatformProblemException(
          HttpStatus.TOO_MANY_REQUESTS,
          "GATEWAY_OVERLOADED",
          "The gateway is intentionally overloaded for the local scenario",
          5);
    }
    ValidatedBatch batch = validator.validate(request);
    GatewayService.IngestionResult result = gateway.accept(batch, failAfterRawStore);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/batches/" + result.batchId()))
        .body(
            Map.of(
                "batchId", result.batchId(),
                "status", result.status(),
                "duplicate", result.duplicate(),
                "objectKey", result.objectKey()));
  }
}
