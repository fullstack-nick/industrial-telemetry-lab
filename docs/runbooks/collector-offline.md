# Runbook: collector offline or stale

Use this runbook for a missing/stale collector alert, an old heartbeat, or an SQLite spool that is not uploading. Do not delete `collector_data`; it is the durable recovery state.

## 1. Inspect heartbeat and inventory

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/collectors/edge-gateway-01 | ConvertTo-Json -Depth 5
Invoke-RestMethod http://localhost:8082/collector/v1/status | ConvertTo-Json -Depth 5
```

Expected fields include last heartbeat, last successful upload, source connectivity, source cursor/epoch, spool rows, oldest unsent age, polling pause, and gap state. A platform inventory heartbeat older than 90 seconds triggers the alert. If the collector endpoint itself is unavailable, continue with container health/logs.

## 2. Separate source and gateway failures

```powershell
docker compose ps controller-simulator edge-collector telemetry-gateway seaweedfs timescaledb rabbitmq
Invoke-RestMethod http://localhost:8081/actuator/health
Invoke-RestMethod http://localhost:8080/actuator/health
docker compose logs --since 10m edge-collector
```

Expected classification:

- source health/read errors plus `sourceConnected=false`: simulator/source path;
- upload retries while source cursor increases: gateway or one of its dependencies;
- `gapDetected=true`: retained source history no longer covers the cursor; no automatic skip;
- high-water `pollingPaused=true`: durable spool capacity protection, not a source outage.

## 3. Inspect spool and host capacity

```powershell
$status = Invoke-RestMethod http://localhost:8082/collector/v1/status
$status | Select-Object spoolObservationCount,oldestUnsentObservationAgeSeconds,pollingPaused,gapDetected
docker system df
docker volume inspect industrial-telemetry-lab_collector_data
```

Expected: normal spool settles near zero. The initial maximum is 100,000 rows; polling pauses at 80,000 and resumes below 60,000. At the default 7.2 observations/second, empty-to-full is about 3 hours 51 minutes, not indefinite buffering. Check free disk before it reaches high water.

## 4. Recover dependencies

Restore the source if polling failed. If uploads failed, restore gateway dependencies first:

```powershell
docker compose start controller-simulator timescaledb seaweedfs rabbitmq telemetry-gateway
docker compose ps
```

Wait for health checks. A database or raw-store outage prevents gateway acknowledgment by design; the collector must retain the batch.

## 5. Restart without resetting the cursor

```powershell
$before = Invoke-RestMethod http://localhost:8082/collector/v1/status
docker compose restart edge-collector
$after = Invoke-RestMethod http://localhost:8082/collector/v1/status
$before.sourceEpoch,$before.sourceCursor,$before.spoolObservationCount
$after.sourceEpoch,$after.sourceCursor,$after.spoolObservationCount
```

Expected: epoch/cursor do not move backward, pending spool rows remain, and any `UPLOADING` batch returns to a retryable state with the same batch ID and compressed checksum. Never use `docker compose down -v` or `reset-local-data` as incident recovery.

## 6. Handle a cursor gap explicitly

Read `gapDetail` and simulator `410 Gone` fields (`sourceEpoch`, `earliestSequence`, `latestSequence`). Determine and record the lost sequence interval. Only after accepting that gap, call the authenticated recovery endpoint with the intended cursor using the schema shown in Swagger UI. The collector must not pick a cursor silently.

## 7. Verify buffered upload and freshness

```powershell
1..6 | ForEach-Object {
  Invoke-RestMethod http://localhost:8082/collector/v1/status |
    Select-Object sourceCursor,spoolObservationCount,oldestUnsentObservationAgeSeconds,lastSuccessfulUploadAt
  Start-Sleep 5
}
```

Expected: source cursor advances when not paused, spool and oldest-unsent age trend down, last successful upload becomes recent, RabbitMQ drains, canonical row count rises, and Grafana end-to-end lag eventually returns below 30 seconds.

Run `scripts/scenarios/normal-operation.ps1` for a bounded reconciliation assertion. Record the outage length, highest spool depth, whether high water or a gap occurred, recovered cursor, and time to freshness recovery.
