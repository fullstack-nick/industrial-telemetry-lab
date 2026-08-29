package dev.telemetrylab.platform.replay;

import dev.telemetrylab.contracts.ReplayRequest;
import dev.telemetrylab.platform.LocalAuthorization;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
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
@RequestMapping("/api/v1/replays")
@ConditionalOnProperty(
    name = "telemetry.platform.mode",
    havingValue = "gateway",
    matchIfMissing = true)
class ReplayApi {
  private final ReplayService replays;
  private final LocalAuthorization authorization;

  ReplayApi(ReplayService replays, LocalAuthorization authorization) {
    this.replays = replays;
    this.authorization = authorization;
  }

  @PostMapping
  ResponseEntity<Map<String, Object>> create(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String bearer,
      @RequestBody ReplayRequest request) {
    authorization.require(bearer);
    Map<String, Object> replay = replays.create(request);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/replays/" + replay.get("replayId")))
        .body(replay);
  }

  @GetMapping("/{replayId}")
  Map<String, Object> get(@PathVariable UUID replayId) {
    return replays.get(replayId);
  }
}
