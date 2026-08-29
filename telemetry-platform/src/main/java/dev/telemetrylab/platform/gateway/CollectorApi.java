package dev.telemetrylab.platform.gateway;

import dev.telemetrylab.contracts.CollectorHeartbeat;
import dev.telemetrylab.platform.LocalAuthorization;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/collectors")
@ConditionalOnProperty(
    name = "telemetry.platform.mode",
    havingValue = "gateway",
    matchIfMissing = true)
class CollectorApi {
  private final CollectorInventoryService inventory;
  private final LocalAuthorization authorization;

  CollectorApi(CollectorInventoryService inventory, LocalAuthorization authorization) {
    this.inventory = inventory;
    this.authorization = authorization;
  }

  @PostMapping("/{collectorId}/heartbeat")
  ResponseEntity<Void> heartbeat(
      @PathVariable String collectorId,
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String bearer,
      @RequestBody CollectorHeartbeat heartbeat) {
    authorization.require(bearer);
    inventory.heartbeat(collectorId, heartbeat);
    return ResponseEntity.accepted().build();
  }

  @GetMapping
  List<Map<String, Object>> all() {
    return inventory.all();
  }

  @GetMapping("/{collectorId}")
  Map<String, Object> one(@PathVariable String collectorId) {
    return inventory.one(collectorId);
  }
}
