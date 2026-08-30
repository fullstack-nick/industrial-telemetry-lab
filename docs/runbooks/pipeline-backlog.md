# Runbook: pipeline backlog

Use this runbook when processing lag or RabbitMQ backlog is increasing. Commands assume the repository root and a populated `.env`.

## 1. Confirm the symptom

Open **Grafana → Pipeline Overview** and **Processing and Storage**. Check `processing_end_to_end_lag_seconds`, `outbox_unpublished_events`, `outbox_oldest_unpublished_age_seconds`, RabbitMQ ready/unacknowledged messages, and database-write failures.

```powershell
Invoke-RestMethod 'http://localhost:9090/api/v1/query?query=histogram_quantile(0.99,sum%20by%20(le)(rate(processing_end_to_end_lag_seconds_bucket%5B5m%5D)))'
Invoke-RestMethod 'http://localhost:15672/api/queues/%2F/telemetry.main' -Credential (Get-Credential)
```

Expected: healthy steady state has a near-zero queue and recent lag below 30 seconds. Record whether growth is in the PostgreSQL outbox, RabbitMQ `messages_ready`, or `messages_unacknowledged`; they imply different boundaries.

For a credential-free queue observation inside the broker:

```powershell
docker compose exec -T rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers state
```

## 2. Classify the failure

```powershell
docker compose ps telemetry-gateway telemetry-worker timescaledb rabbitmq seaweedfs
docker compose logs --since 10m telemetry-worker telemetry-gateway
docker compose exec -T timescaledb pg_isready -U telemetry -d telemetry
```

Expected observations:

| Observation | Likely boundary |
| --- | --- |
| Outbox grows, main queue does not | Dispatcher cannot reach/confirm RabbitMQ |
| Main queue ready grows, worker has zero consumer or is unhealthy | Worker stopped or disconnected |
| Unacknowledged stays high and DB-write failures rise | Worker is blocked on TimescaleDB |
| Retry queues/DLQ grow with healthy DB | Raw-object, checksum, mapping, or processing error |
| Worker throughput exists but is below ingest rate | Slow worker or constrained host |

Do not purge queues or delete database rows. Persistent duplicate references are safe; lost references are not.

## 3. Inspect logs and traces

In Grafana Explore, query Loki with `{service_name="telemetry-worker"}` and filter the incident time. Use a log's trace ID to open Tempo. Follow queue receive → raw-object fetch/checksum → mapping → database transaction. Secrets and raw payloads should not appear.

From the shell:

```powershell
docker compose logs --since 10m telemetry-worker | Select-String 'ERROR|WARN|retry|dead-letter|database'
```

Expected: retryable failures name the batch and attempt without credentials; terminal failure appears only after confirmed DLQ publication.

## 4. Recover the dependency first

If TimescaleDB or SeaweedFS is unhealthy, restore it and wait for its health check before touching the worker:

```powershell
docker compose start timescaledb seaweedfs rabbitmq
docker compose up -d database-migrate
docker compose ps
```

The migration service should exit zero. Do not rerun a destructive reset.

## 5. Restart the worker safely

```powershell
docker compose restart telemetry-worker
docker compose ps telemetry-worker
docker compose logs --since 2m telemetry-worker
```

Manual acknowledgments ensure an interrupted in-flight message is redelivered. The database identity constraint makes that repeat idempotent.

## 6. Verify drain and freshness recovery

```powershell
docker compose exec -T rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
docker compose exec -T timescaledb psql -U telemetry -d telemetry -Atc "SELECT processing_status, count(*) FROM ingestion_batch GROUP BY processing_status ORDER BY processing_status"
```

Expected: ready/unacknowledged counts trend to zero, `PROCESSED` manifests increase, database-write errors stop, and the p99 end-to-end lag returns below 30 seconds after catch-up. Recheck twice rather than relying on one instantaneous sample.

## 7. Handle dead-lettered batches

Inspect before acting:

```powershell
docker compose exec -T rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged
docker compose exec -T timescaledb psql -U telemetry -d telemetry -P pager=off -c "SELECT batch_id, processing_status, last_error FROM ingestion_batch WHERE processing_status='FAILED' ORDER BY received_at DESC"
```

Fix the underlying raw-store, mapping, rules, or database problem. Do not move an opaque DLQ message back automatically. Create an explicit replay for the affected event-time interval and pinned versions through `POST /api/v1/replays`; this preserves an audit trail and reuses the same raw object. Keep the permanent DLQ message until the replay is verified.

## 8. Prove idempotency

```powershell
docker compose exec -T timescaledb psql -U telemetry -d telemetry -Atc "SELECT count(*) FROM (SELECT observation_id FROM telemetry_sample_identity GROUP BY observation_id HAVING count(*) > 1) d"
docker compose exec -T timescaledb psql -U telemetry -d telemetry -Atc "SELECT count(*) FROM (SELECT observation_id, observed_at FROM telemetry_sample GROUP BY observation_id, observed_at HAVING count(*) > 1) d"
```

Both commands must return `0`. Confirm the restored interval through `/api/v1/telemetry`, then record incident start/end, dependency cause, maximum queue/outbox/lag, recovery action, replay ID if any, and the two uniqueness results.
