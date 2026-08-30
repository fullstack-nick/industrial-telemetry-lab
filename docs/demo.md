# Demonstration guide

This walkthrough takes about five minutes after images and Maven dependencies are cached. It uses only synthetic local data.

## Prepare

```powershell
Copy-Item .env.example .env -ErrorAction SilentlyContinue
.\scripts\run-demo.ps1
```

```bash
cp -n .env.example .env
./scripts/run-demo.sh
```

Expected result: every core and observability service is healthy, Prometheus reports six scrape endpoints, Grafana has four provisioned dashboards, and canonical telemetry is increasing.

Open:

- Grafana: <http://localhost:3000/dashboards>
- Swagger UI: <http://localhost:8080/swagger-ui.html>
- RabbitMQ: <http://localhost:15672>
- SeaweedFS filer: <http://localhost:8888>

## 1. Establish normal operation

Open **Pipeline Overview**, **Collector Health**, and **Processing and Storage**. The collector spool and RabbitMQ backlog should settle near zero, ingestion and canonical counters should increase, and recent processing lag should remain below 30 seconds.

Query a recent interval through Swagger or substitute current UTC timestamps:

```powershell
$to = [DateTimeOffset]::UtcNow
$from = $to.AddMinutes(-10)
$uri = "http://localhost:8080/api/v1/telemetry?facilityId=facility-alpha&assetId=zone-07&from=$([uri]::EscapeDataString($from.ToString('o')))&to=$([uri]::EscapeDataString($to.ToString('o')))&pageSize=10"
Invoke-RestMethod $uri
```

Point out `observedAt`, `receivedAt`, `processedAt`, provenance, mapping version, and the opaque next cursor.

## 2. Demonstrate edge buffering

```powershell
.\scripts\scenarios\gateway-outage.ps1
```

The script stops the gateway, proves that source cursor and SQLite spool keep increasing, restarts the gateway, and waits for the durable backlog to drain. In **Collector Health**, the spool and oldest-unsent panels rise during the outage and recover afterward.

## 3. Demonstrate queue decoupling

```powershell
.\scripts\scenarios\worker-backlog.ps1
```

While the worker is stopped, the gateway continues storing raw objects and manifests, and `telemetry.main` grows. After restart, the queue drains and canonical writes resume. The collector never needs worker awareness.

## 4. Demonstrate quality and replay

```powershell
.\scripts\scenarios\unknown-tag-and-replay.ps1
```

The simulator emits `TEMP_AUX_PV`. Mapping 1.0 rejects it with `UNKNOWN_SOURCE_TAG`, but the exact raw object remains. The script creates a replay pinned to mapping 1.1, proves the auxiliary samples become canonical, repeats the replay, and proves the canonical count stays fixed. Open **Data Quality** to show rejected and duplicate outcomes.

## 5. Inspect one batch

Get a recent ID:

```powershell
$batchId = docker compose exec -T timescaledb psql -U telemetry -d telemetry -Atc "SELECT batch_id FROM ingestion_batch ORDER BY received_at DESC LIMIT 1"
Invoke-RestMethod "http://localhost:8080/api/v1/batches/$batchId" | ConvertTo-Json -Depth 8
```

The response connects object key/checksum, manifest counts, processing attempts, version choices, and any precise rejections. Use the object key in the SeaweedFS filer to show that the raw gzip input remains independent of the projection.

## 6. Follow a trace

In Grafana, open **Explore**, select **Tempo**, and search recent service traces. Choose a collector upload whose connected trace includes gateway validation/raw persistence and downstream outbox/worker processing. Correlate its `trace.id`, `batchId`, and service names with Loki logs. Asynchronous context is persisted through the outbox and injected into RabbitMQ headers.

## 7. Use an incident runbook

Run `worker-backlog` again and follow `docs/runbooks/pipeline-backlog.md`: confirm lag, inspect queue depth, distinguish worker versus database trouble, restart safely, verify drain, and check identity uniqueness. This is the operational-ownership part of the demonstration.

## Full evidence run

```powershell
.\scripts\run-end-to-end-tests.ps1
```

Each scenario has bounded polling and explicit PASS assertions. For a full release-style run, use `scripts/verify-local.ps1`, which also verifies formatting, tests, static analysis, Compose, dashboards, OpenAPI, security invariants, and stack health.

## Cleanup

Preserve data for later inspection:

```powershell
docker compose --profile observability down
```

Remove only this project's data after reading the exact target list and confirming:

```powershell
.\scripts\reset-local-data.ps1
```
