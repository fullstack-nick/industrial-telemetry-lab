package dev.telemetrylab.platform.gateway;

import dev.telemetrylab.platform.LocalAuthorization;
import dev.telemetrylab.platform.messaging.OutboxRuntimeControl;
import dev.telemetrylab.platform.store.RawObjectStore;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@ConditionalOnProperty(
    name = "telemetry.platform.mode",
    havingValue = "gateway",
    matchIfMissing = true)
class AdminApi {
  private final LocalAuthorization authorization;
  private final GatewayRuntimeControl runtimeControl;
  private final RawObjectStore rawObjectStore;
  private final JdbcTemplate jdbc;
  private final OutboxRuntimeControl outboxRuntimeControl;

  AdminApi(
      LocalAuthorization authorization,
      GatewayRuntimeControl runtimeControl,
      RawObjectStore rawObjectStore,
      JdbcTemplate jdbc,
      OutboxRuntimeControl outboxRuntimeControl) {
    this.authorization = authorization;
    this.runtimeControl = runtimeControl;
    this.rawObjectStore = rawObjectStore;
    this.jdbc = jdbc;
    this.outboxRuntimeControl = outboxRuntimeControl;
  }

  @PutMapping("/overload")
  Map<String, Boolean> overload(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String bearer,
      @RequestBody OverloadRequest request) {
    authorization.require(bearer);
    return Map.of("overloaded", runtimeControl.setOverloaded(request.overloaded()));
  }

  @PutMapping("/faults/outbox-confirm-gap")
  Map<String, Boolean> outboxConfirmGap(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String bearer,
      @RequestBody OutboxConfirmGapRequest request) {
    authorization.require(bearer);
    return Map.of(
        "armed", outboxRuntimeControl.armConfirmCommitGap(request.armed(), request.batchId()));
  }

  @GetMapping("/raw-objects/reconciliation")
  Map<String, Object> reconcile(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String bearer) {
    authorization.require(bearer);
    Set<String> manifestKeys =
        new HashSet<>(jdbc.queryForList("SELECT object_key FROM ingestion_batch", String.class));
    Set<String> objectKeys = new HashSet<>();
    for (RawObjectStore.StoredObjectMetadata object : rawObjectStore.list()) {
      objectKeys.add(object.objectKey());
    }
    List<String> orphanObjects =
        objectKeys.stream().filter(key -> !manifestKeys.contains(key)).sorted().toList();
    List<String> missingObjects =
        manifestKeys.stream().filter(key -> !objectKeys.contains(key)).sorted().toList();
    return Map.of(
        "orphanObjects",
        orphanObjects,
        "missingObjects",
        missingObjects,
        "healthy",
        orphanObjects.isEmpty() && missingObjects.isEmpty());
  }

  record OverloadRequest(boolean overloaded) {}

  record OutboxConfirmGapRequest(boolean armed, UUID batchId) {}
}
