# Project plan: **Industrial Telemetry Lab**

**Repository name:** `industrial-telemetry-lab`

**Public description:**

> A fully local, reliability-focused industrial telemetry platform demonstrating durable edge buffering, at-least-once ingestion, idempotent processing, time-series storage, replay, data-quality validation, and observability using Java and Docker Compose.

The repository should contain no employer name, product name, job title, job-posting text, company terminology, logos, links, or domain-specific concepts associated with the position. All facilities, devices, signals, and operational scenarios are fictional and generic.

---

# 1. Project idea

Build a local telemetry platform for a fictional climate-controlled industrial facility.

The facility contains **twelve independently controlled zones**. A legacy control system produces temperature, humidity, and ventilation readings using vendor-specific tag names. An edge collector must reliably retrieve those readings and send them to a central local platform.

The central platform must:

* Durably accept telemetry.
* Handle temporary connectivity failures.
* Apply versioned mappings and validation rules.
* Detect duplicates and out-of-order readings.
* Store canonical time-series data.
* Preserve the original input.
* Replay historical raw data.
* Expose telemetry through a REST API.
* Provide logs, metrics, traces, alerts, and runbooks.

The project is not primarily a dashboard. The interesting part is what happens when the system is slow, disconnected, duplicated, misconfigured, or partially unavailable.

---

# 2. Fictional internal assignment

Use this as the project’s problem statement:

> A climate-controlled industrial facility has a legacy control system that exposes machine readings through an HTTP interface. The control system uses source-specific tag names and cannot be accessed directly by downstream applications.
>
> Build a reliable telemetry platform that creates a stable boundary between the control system and application developers. The platform must support intermittent connectivity, durable buffering, data validation, time-series querying, deduplication, and replay after processing or configuration errors.

A downstream developer should be able to request:

```text
Return the temperature history for zone-07 between 08:00 and 12:00.
```

They should not need to understand a source tag such as:

```text
CTRL_A.ZONE[07].TEMP_PV
```

---

# 3. Deliberately small scope

Model only:

| Area                  | Scope                                                     |
| --------------------- | --------------------------------------------------------- |
| Facility              | One fictional facility                                    |
| Assets                | Twelve climate-control zones                              |
| Signals               | Three signals per zone                                    |
| Sampling interval     | One sample every five seconds                             |
| Source systems        | One simulated control system                              |
| Edge software         | One collector instance                                    |
| Platform applications | One platform codebase running in gateway and worker modes |
| Storage               | One local time-series database                            |
| Replay                | Time-range and batch-based replay                         |
| User interface        | REST/OpenAPI and Grafana only                             |
| Deployment            | Docker Compose on one development machine                 |
| CI/CD                 | None                                                      |

The three canonical signals are:

```text
environment.air_temperature
environment.relative_humidity
ventilation.output
```

At this scale, the simulator produces:

* 36 observations every five seconds.
* 7.2 observations per second.
* 25,920 observations per hour.
* 622,080 observations per day.
* 4,320 observations during a ten-minute platform outage.

That is small enough for a laptop but large enough to demonstrate backlog growth, replay, query performance, and time-series behavior.

---

# 4. Explicit non-goals

Do not build:

* Any cloud infrastructure.
* Terraform or another cloud provisioning system.
* GitHub Actions or another CI/CD pipeline.
* Kubernetes.
* Kafka.
* A real industrial-controller integration.
* A complete operator frontend.
* A mobile application.
* Machine learning or predictive maintenance.
* Multi-region or multi-datacenter failover.
* Multi-tenant billing or account management.
* Real firmware OTA.
* Complex user authentication.
* A complete digital twin.
* Production-grade certificate management.
* A high-availability database cluster.

The purpose is to demonstrate a reliable telemetry path, not to reproduce an entire industrial software platform.

---

# 5. Local technology stack

| Concern                          | Technology                                         |
| -------------------------------- | -------------------------------------------------- |
| Application language             | Java 21                                            |
| Application framework            | Spring Boot                                        |
| Build system                     | Maven Wrapper                                      |
| Local orchestration              | Docker Compose                                     |
| Collector spool                  | SQLite                                             |
| Raw object storage               | MinIO                                              |
| Message queue                    | RabbitMQ                                           |
| Time-series and metadata storage | PostgreSQL with TimescaleDB                        |
| Database migrations              | Flyway                                             |
| Metrics                          | Micrometer and Prometheus                          |
| Tracing                          | OpenTelemetry Collector and Tempo                  |
| Logs                             | Structured JSON and Loki                           |
| Dashboards                       | Grafana                                            |
| API documentation                | OpenAPI and Swagger UI                             |
| Contract definitions             | JSON Schema and YAML                               |
| Integration testing              | JUnit and Testcontainers                           |
| Resilience testing               | Local scenario scripts and Docker Compose commands |

Use explicit pinned Docker image versions in the repository rather than `latest`.

There should be no packages or abstractions named after cloud products. Use interfaces such as:

```java
RawObjectStore
EventQueue
TelemetryStore
SourceAdapter
```

Their local implementations can use MinIO, RabbitMQ, TimescaleDB, and HTTP.

---

# 6. Architecture

```text
┌──────────────────────────┐
│ Control-System Simulator │
│                          │
│ Vendor-style tags        │
│ Cursor-based HTTP API    │
│ Fault injection          │
└────────────┬─────────────┘
             │ poll
             ▼
┌──────────────────────────┐
│ Edge Collector           │
│                          │
│ Source adapter           │
│ SQLite durable spool     │
│ Cursor persistence       │
│ Batch creation           │
│ Retry and backoff        │
│ Heartbeats               │
└────────────┬─────────────┘
             │ compressed HTTP batches
             ▼
┌──────────────────────────┐
│ Telemetry Gateway        │
│                          │
│ Authentication           │
│ Envelope validation      │
│ Idempotency checks       │
│ Raw object persistence   │
│ Batch manifest           │
│ Transactional outbox     │
└───────┬──────────┬───────┘
        │          │
        │          ▼
        │    ┌──────────────────┐
        │    │ PostgreSQL       │
        │    │                  │
        │    │ Batch manifest   │
        │    │ Outbox           │
        │    │ Collector status │
        │    └────────┬─────────┘
        │             │ outbox publisher
        ▼             ▼
┌──────────────┐   ┌──────────────────┐
│ MinIO        │   │ RabbitMQ         │
│              │   │                  │
│ Raw batches  │   │ Main queue       │
│ Quarantine   │   │ Retry queues     │
└──────┬───────┘   │ Dead-letter queue│
       │           └────────┬─────────┘
       │                    │
       └──────────────┐     │ consume
                      ▼     ▼
                ┌──────────────────────┐
                │ Telemetry Worker     │
                │                      │
                │ Mapping              │
                │ Validation           │
                │ Data-quality rules   │
                │ Deduplication        │
                │ Time-series writes   │
                │ Processing audit     │
                └──────────┬───────────┘
                           ▼
                ┌──────────────────────┐
                │ TimescaleDB          │
                │                      │
                │ Canonical telemetry  │
                │ Rejections           │
                │ Replay records       │
                └──────────┬───────────┘
                           │
                           ▼
                ┌──────────────────────┐
                │ Query and Replay API │
                └──────────────────────┘
```

The gateway, query API, replay API, and outbox publisher can live in the same Java application. Run that application in two modes:

```text
APP_MODE=gateway
APP_MODE=worker
```

This preserves meaningful process boundaries without creating an excessive number of repositories or microservices.

---

# 7. Component details

## 7.1 Control-system simulator

The simulator represents a fictional legacy industrial control system.

It should generate readings for:

```text
zone-01 through zone-12
```

Source tags:

```text
CTRL_A.ZONE[07].TEMP_PV
CTRL_A.ZONE[07].RH_PV
CTRL_A.ZONE[07].FAN_OUT
```

Example API:

```http
GET /controller/v1/readings?afterSequence=184200&limit=500
```

Example response:

```json
{
  "sourceEpoch": "47af63a8-85c4-4a34-9b60-f63d6e16f564",
  "nextSequence": 184203,
  "readings": [
    {
      "sequence": 184201,
      "tag": "CTRL_A.ZONE[07].TEMP_PV",
      "observedAt": "2026-08-29T08:41:20.217Z",
      "value": 29.4,
      "unit": "degC",
      "qualityCode": 192
    },
    {
      "sequence": 184202,
      "tag": "CTRL_A.ZONE[07].RH_PV",
      "observedAt": "2026-08-29T08:41:20.217Z",
      "value": 67.1,
      "unit": "%",
      "qualityCode": 192
    }
  ]
}
```

`sourceEpoch` distinguishes readings across a control-system sequence reset.

The simulator must support fault injection:

```text
duplicateRate
outOfOrderRate
invalidUnitRate
badQualityRate
futureTimestampRate
responseDelay
connectionAvailable
newUnknownTagEnabled
```

Provide a small administrative API:

```http
PUT /controller/v1/faults
```

Example:

```json
{
  "duplicateRate": 0.05,
  "outOfOrderRate": 0.03,
  "invalidUnitRate": 0.01,
  "responseDelayMs": 500
}
```

The simulator should retain enough history for the collector to resume from a cursor after a temporary interruption.

---

## 7.2 Edge collector

The collector represents software running near machinery.

Its responsibilities are:

1. Poll the source using a persisted cursor.
2. Convert the source response into a stable raw-observation contract.
3. Retain original source tags, values, units, and quality codes.
4. Persist observations to SQLite before advancing the cursor.
5. Group unsent observations into deterministic outbound batches.
6. Retry failed uploads with exponential backoff and jitter.
7. Stop polling when its local spool reaches a high-water mark.
8. Resume polling after the spool falls below a low-water mark.
9. Send periodic collector heartbeats.
10. Survive restarts without losing its cursor or unsent observations.

Suggested SQLite tables:

```text
collector_state
spool_observation
outbound_batch
outbound_batch_item
```

Important transaction:

```text
BEGIN

Insert newly fetched observations into spool_observation.
Update the persisted source cursor.

COMMIT
```

The cursor must never advance before the corresponding observations are durable.

Batch behavior:

* A batch ID is generated once and persisted.
* Retrying an upload must reuse the same batch ID.
* The exact batch contents must not change between retry attempts.
* Observations are marked acknowledged only after the gateway returns success.
* Acknowledged observations can be retained briefly before being pruned.

Backpressure configuration:

```yaml
spool:
  maxRows: 100000
  highWatermarkPercent: 80
  lowWatermarkPercent: 60
```

When the spool reaches 80%, polling pauses. It resumes below 60%. If the spool is completely full, the collector must emit a critical metric and log rather than silently dropping readings.

---

## 7.3 Telemetry gateway

The gateway receives batches from collectors.

Endpoint:

```http
POST /api/v1/ingestion/batches
Content-Encoding: gzip
Authorization: Bearer <local-development-token>
```

The gateway performs only inexpensive synchronous validation:

* Authentication.
* Maximum request size.
* Gzip validity.
* JSON envelope structure.
* Supported contract version.
* Collector identity.
* Batch ID and checksum validation.

It should not synchronously process every observation before responding.

Processing sequence:

1. Calculate the payload checksum.
2. Check whether the batch ID already exists.
3. Write the exact compressed request body to MinIO.
4. Insert the batch manifest and an outbox event into PostgreSQL.
5. Commit the database transaction.
6. Return `202 Accepted`.

Suggested raw object key:

```text
raw-observations/
  facility=facility-alpha/
  date=2026-08-29/
  hour=08/
  collector=edge-gateway-01/
  batch=<batch-id>.json.gz
```

Duplicate behavior:

| Situation                                   | Result                                            |
| ------------------------------------------- | ------------------------------------------------- |
| New batch ID                                | Store and return `202`                            |
| Existing batch ID with identical checksum   | Return success without storing another copy       |
| Existing batch ID with a different checksum | Return `409 Conflict`                             |
| Unsupported envelope version                | Return `400 Bad Request`                          |
| Payload too large                           | Return `413 Payload Too Large`                    |
| Gateway intentionally overloaded            | Return `429 Too Many Requests` with `Retry-After` |

A batch is acknowledged only after its raw payload and manifest are durable.

---

## 7.4 Transactional outbox

Do not directly write to MinIO, publish to RabbitMQ, and assume both operations always succeed together.

The gateway writes an outbox record in the same PostgreSQL transaction as the batch manifest. A background publisher reads unpublished outbox records and sends persistent RabbitMQ messages.

Outbox record:

```text
event_id
event_type
batch_id
payload
created_at
published_at
attempt_count
last_error
```

RabbitMQ messages should contain only a reference:

```json
{
  "eventType": "RawBatchStored",
  "batchId": "81f50d40-7742-457b-bf16-e602e17d26ce",
  "objectKey": "raw-observations/facility=facility-alpha/...",
  "checksum": "sha256:..."
}
```

The complete batch stays in MinIO.

This lets the system retry queue publication without resending or reconstructing the raw telemetry.

---

## 7.5 RabbitMQ processing topology

Use:

```text
telemetry.main
telemetry.retry.short
telemetry.retry.medium
telemetry.dead-letter
```

The worker uses manual acknowledgement.

A message is acknowledged only after the processing transaction has committed.

Suggested retry behavior:

```text
First retry:   5 seconds
Second retry: 30 seconds
Third retry:   2 minutes
Maximum attempts before dead-lettering: 5
```

The exact delays are less important than proving:

* Temporary failures retry automatically.
* Messages are not acknowledged too early.
* Poison messages do not retry forever.
* Dead-letter messages remain recoverable through replay.

---

## 7.6 Telemetry worker

The worker:

1. Consumes a batch reference.
2. Loads the raw batch from MinIO.
3. Verifies its checksum.
4. Validates each raw observation.
5. Maps source tags to canonical asset and signal identifiers.
6. Applies data-quality rules.
7. Generates a deterministic observation ID.
8. Deduplicates.
9. Writes canonical samples.
10. Records rejected observations.
11. Updates batch processing status.
12. Acknowledges the queue message.

The deterministic observation ID can be based on:

```text
facilityId
sourceSystem
sourceEpoch
sourceSequence
sourceTag
```

Conceptually:

```text
SHA-256(
  facilityId
  + sourceSystem
  + sourceEpoch
  + sourceSequence
  + sourceTag
)
```

Do not generate a new random event ID each time an observation is processed. A stable ID is necessary for correct replay and deduplication.

---

# 8. Data contracts

Use two separate contracts.

## 8.1 Raw observation batch

This is the contract between the edge collector and the platform.

```json
{
  "contractVersion": "raw-observation.batch.v1",
  "batchId": "81f50d40-7742-457b-bf16-e602e17d26ce",
  "collectorId": "edge-gateway-01",
  "collectorVersion": "1.0.0",
  "facilityId": "facility-alpha",
  "createdAt": "2026-08-29T08:41:25.000Z",
  "observations": [
    {
      "sourceSystem": "controller-a",
      "sourceEpoch": "47af63a8-85c4-4a34-9b60-f63d6e16f564",
      "sourceSequence": 184201,
      "sourceTag": "CTRL_A.ZONE[07].TEMP_PV",
      "observedAt": "2026-08-29T08:41:20.217Z",
      "rawValue": 29.4,
      "rawUnit": "degC",
      "sourceQualityCode": 192
    }
  ]
}
```

The raw contract preserves enough source information to reinterpret observations later.

## 8.2 Canonical telemetry sample

This is the processed representation exposed to downstream applications.

```json
{
  "contractVersion": "telemetry.sample.v1",
  "observationId": "sha256-value",
  "facilityId": "facility-alpha",
  "assetId": "zone-07",
  "signalId": "environment.air_temperature",
  "observedAt": "2026-08-29T08:41:20.217Z",
  "receivedAt": "2026-08-29T08:41:25.411Z",
  "processedAt": "2026-08-29T08:41:26.102Z",
  "value": 29.4,
  "unit": "Cel",
  "quality": "GOOD",
  "flags": [],
  "source": {
    "sourceSystem": "controller-a",
    "sourceEpoch": "47af63a8-85c4-4a34-9b60-f63d6e16f564",
    "sourceSequence": 184201,
    "sourceTag": "CTRL_A.ZONE[07].TEMP_PV",
    "collectorId": "edge-gateway-01",
    "collectorVersion": "1.0.0",
    "mappingVersion": "controller-a-mapping-1.0.0",
    "qualityRulesVersion": "quality-rules-1.0.0"
  }
}
```

Keep these timestamps separate:

* `observedAt`: when the source created the reading.
* `receivedAt`: when the gateway received it.
* `processedAt`: when the worker produced the canonical sample.

---

# 9. Versioned source mapping

Store mappings as versioned YAML files:

```text
config/mappings/controller-a-mapping-1.0.0.yaml
```

Example:

```yaml
mappingVersion: controller-a-mapping-1.0.0

rules:
  - tagPattern: 'CTRL_A\.ZONE\[(?<zone>\d{2})\]\.TEMP_PV'
    assetIdTemplate: 'zone-${zone}'
    signalId: 'environment.air_temperature'
    expectedSourceUnit: 'degC'
    canonicalUnit: 'Cel'

  - tagPattern: 'CTRL_A\.ZONE\[(?<zone>\d{2})\]\.RH_PV'
    assetIdTemplate: 'zone-${zone}'
    signalId: 'environment.relative_humidity'
    expectedSourceUnit: '%'
    canonicalUnit: '%'

  - tagPattern: 'CTRL_A\.ZONE\[(?<zone>\d{2})\]\.FAN_OUT'
    assetIdTemplate: 'zone-${zone}'
    signalId: 'ventilation.output'
    expectedSourceUnit: '%'
    canonicalUnit: '%'
```

Mapping rules must be tested independently.

Versioning rules:

* Existing mapping files are immutable.
* Corrections create a new version.
* A replay run records the mapping version it used.
* Raw observations always retain the original source tag.
* Unknown tags are rejected rather than silently ignored.

Contract changes follow similar rules:

* Additive optional fields may remain in version 1.
* Required-field removal or semantic changes require version 2.
* A field must never be reused with a different meaning.

---

# 10. Data-quality outcomes

Every observation produces one of four outcomes:

| Outcome   | Meaning                                            |
| --------- | -------------------------------------------------- |
| Accepted  | Valid and queryable                                |
| Flagged   | Queryable, but has one or more warnings            |
| Rejected  | Cannot be interpreted safely                       |
| Duplicate | Previously processed; no additional sample written |

Example rules:

| Condition                                       | Outcome                     |
| ----------------------------------------------- | --------------------------- |
| Missing source tag                              | Rejected                    |
| Unknown source tag                              | Rejected                    |
| Unsupported raw unit                            | Rejected                    |
| Non-numeric, NaN, or infinite value             | Rejected                    |
| Temperature outside configured physical limits  | Rejected                    |
| Humidity below 0% or above 100%                 | Rejected                    |
| Ventilation output below 0% or above 100%       | Rejected                    |
| Timestamp slightly in the future                | Flagged                     |
| Observation arrives more than five minutes late | Flagged                     |
| Source quality code indicates bad quality       | Accepted with `quality=BAD` |
| Sequence is older than the last seen sequence   | Flagged as out of order     |
| Existing observation ID                         | Duplicate                   |

Do not silently discard rejected data.

For each rejection, retain:

```text
observation_id
batch_id
source_tag
reason_code
human_readable_reason
mapping_version
quality_rules_version
processing_attempt_id
created_at
```

The complete original observation remains in the raw MinIO object.

Optionally write one rejection report per batch:

```text
quarantine/
  date=2026-08-29/
  batch=<batch-id>-rejections.json
```

---

# 11. Database model

Use TimescaleDB for both time-series data and ordinary platform metadata.

Suggested tables:

```text
ingestion_batch
outbox_event
collector_status
telemetry_sample
telemetry_rejection
processing_attempt
replay_run
replay_batch
```

## `telemetry_sample`

Important columns:

```text
observation_id
facility_id
asset_id
signal_id
observed_at
received_at
processed_at
value_double
unit
quality
flags
source_system
source_sequence
source_epoch
source_tag
collector_id
mapping_version
quality_rules_version
raw_batch_id
```

Make `telemetry_sample` a TimescaleDB hypertable partitioned by `observed_at`.

Use a uniqueness strategy that prevents another row from being created for the same stable observation ID.

## `ingestion_batch`

Track:

```text
batch_id
collector_id
facility_id
checksum
object_key
received_at
minimum_observed_at
maximum_observed_at
observation_count
processing_status
accepted_count
flagged_count
rejected_count
duplicate_count
last_error
```

The manifest makes it possible to locate raw batches for a time-range replay without scanning every MinIO object.

---

# 12. REST API

## Signal registry

```http
GET /api/v1/signals
```

Response includes canonical signal name, unit, value type, and valid range.

## Telemetry query

```http
GET /api/v1/telemetry
    ?facilityId=facility-alpha
    &assetId=zone-07
    &signalId=environment.air_temperature
    &from=2026-08-29T08:00:00Z
    &to=2026-08-29T12:00:00Z
```

Support:

* Event-time ordering.
* Pagination.
* Maximum query range.
* Optional inclusion of flagged or bad-quality readings.
* Basic downsampling only if needed later.

Do not build a general analytics engine.

## Batch inspection

```http
GET /api/v1/batches/{batchId}
```

Return processing counts, raw object reference, status, retries, and errors.

## Collector inventory

```http
GET /api/v1/collectors
GET /api/v1/collectors/{collectorId}
```

Expose:

```text
collector ID
software version
configuration version
last heartbeat
last successful upload
spool row count
oldest unsent observation age
current status
```

## Replay creation

```http
POST /api/v1/replays
```

Example:

```json
{
  "facilityId": "facility-alpha",
  "from": "2026-08-29T08:00:00Z",
  "to": "2026-08-29T09:00:00Z",
  "mappingVersion": "controller-a-mapping-1.1.0",
  "reason": "Reprocess observations rejected because of an unknown source tag"
}
```

## Replay inspection

```http
GET /api/v1/replays/{replayId}
```

Return:

```text
status
requested time range
requested mapping version
matching batch count
processed observation count
accepted count
rejected count
duplicate count
started at
completed at
```

---

# 13. Replay and reprocessing

Replay must use the exact same worker logic as live processing.

The replay service:

1. Queries `ingestion_batch` for matching raw batches.
2. Creates a `replay_run`.
3. Publishes batch references to RabbitMQ with replay metadata.
4. Lets the normal worker load and process the original raw objects.
5. Records replay results.

Example queue message:

```json
{
  "eventType": "ReplayRawBatch",
  "replayId": "a03ce684-37dd-4191-a829-b21f26638863",
  "batchId": "81f50d40-7742-457b-bf16-e602e17d26ce",
  "objectKey": "raw-observations/facility=facility-alpha/...",
  "mappingVersion": "controller-a-mapping-1.1.0"
}
```

Primary replay scenario:

1. The simulator starts emitting a new tag.
2. The current mapping does not recognize it.
3. Observations are retained raw and recorded as rejected.
4. A new immutable mapping version is added.
5. The worker is restarted with the new mapping.
6. The rejected time range is replayed.
7. The observations become valid canonical samples.
8. Running the same replay again creates no duplicate samples.

Rebuilding already-accepted projections under a completely different mapping is outside the initial scope. The implemented case is reprocessing previously rejected or incompletely processed observations.

---

# 14. Collector heartbeat and lightweight fleet inventory

Every 30 seconds, the collector sends:

```http
POST /api/v1/collectors/edge-gateway-01/heartbeat
```

Example:

```json
{
  "collectorVersion": "1.0.0",
  "configurationVersion": "facility-alpha-config-1.0.0",
  "sourceAdapterVersion": "http-controller-adapter-1.0.0",
  "spoolObservationCount": 4312,
  "oldestUnsentObservationAgeSeconds": 487,
  "lastSuccessfulUploadAt": "2026-08-29T08:35:00Z",
  "sourceConnected": true
}
```

This gives you fleet-inventory fundamentals without implementing actual firmware deployment.

Actual OTA and rollout rings remain outside the core project. Document them in a future-design ADR rather than implementing them.

---

# 15. Observability

Use OpenTelemetry and Micrometer across all Java applications.

## Metrics

Collector:

```text
edge_source_poll_total{result}
edge_source_observations_received_total
edge_spool_observations
edge_spool_utilization_ratio
edge_oldest_unsent_age_seconds
edge_upload_total{result}
edge_upload_retry_total
edge_last_successful_upload_timestamp
```

Gateway:

```text
ingestion_batches_total{result}
ingestion_observations_received_total
ingestion_request_duration_seconds
ingestion_raw_store_duration_seconds
ingestion_duplicate_batches_total
outbox_unpublished_events
outbox_oldest_unpublished_age_seconds
outbox_publish_total{result}
```

Worker:

```text
processing_batches_total{result}
processing_observations_total{outcome,reason}
processing_duration_seconds
processing_end_to_end_lag_seconds
processing_out_of_order_total
processing_duplicates_total
processing_database_write_total{result}
```

Replay:

```text
replay_runs_total{result}
replay_batches_total{result}
replay_observations_total{outcome}
replay_duration_seconds
```

Collector inventory:

```text
collector_last_heartbeat_age_seconds
collector_spool_observations
collector_oldest_unsent_age_seconds
```

## Logs

All applications should write structured JSON logs.

Important fields:

```text
timestamp
level
service
traceId
spanId
collectorId
batchId
observationId
replayId
sourceTag
outcome
reasonCode
```

Avoid logging complete large payloads.

## Traces

Trace important flows:

```text
Source poll
  → SQLite persistence
  → Batch upload
  → Raw object write
  → Manifest transaction
  → Outbox publication
  → Queue consumption
  → Validation
  → Time-series write
```

Queue messages should propagate tracing context where practical.

## Grafana dashboards

Create four dashboards:

1. **Pipeline Overview**

   * Received, accepted, rejected, duplicate, and flagged rates.
   * End-to-end processing lag.
   * Current queue depth.
   * Current collector status.

2. **Collector Health**

   * SQLite spool size.
   * Oldest unsent observation.
   * Upload failures.
   * Source polling failures.
   * Last heartbeat.

3. **Processing and Storage**

   * Worker throughput.
   * Processing latency.
   * RabbitMQ backlog.
   * Database write failures.
   * Dead-letter count.

4. **Data Quality**

   * Rejection percentage.
   * Rejections by reason.
   * Unknown source tags.
   * Invalid units.
   * Bad-quality source readings.
   * Out-of-order observations.

---

# 16. SLOs and operational invariants

Define one primary SLO:

> When the local source, network, and platform dependencies are healthy, 99% of good-quality observations must become queryable within 30 seconds.

Track this using:

```text
processedAt - observedAt
```

Also define a durability invariant:

> Any batch acknowledged by the gateway must remain recoverable from the raw object store and batch manifest after application restarts.

And an idempotency invariant:

> Processing the same raw batch any number of times must not create more than one canonical sample for the same observation ID.

These are more meaningful than simply measuring request uptime.

---

# 17. Alert rules

Include local Prometheus or Grafana alert definitions for:

| Alert                   | Example condition                                |
| ----------------------- | ------------------------------------------------ |
| Collector missing       | No heartbeat for more than 90 seconds            |
| Collector spool high    | Spool usage above 80%                            |
| Old unsent data         | Oldest unsent observation above five minutes     |
| Processing lag          | 99th percentile lag above 30 seconds             |
| Queue backlog           | Oldest queued message above one minute           |
| Outbox stuck            | Oldest unpublished outbox event above 30 seconds |
| Rejection spike         | Rejection ratio above 5% for five minutes        |
| Dead-letter messages    | Dead-letter queue is non-empty                   |
| Database write failures | Repeated worker database failures                |
| Raw object failures     | Gateway cannot persist raw batches               |

Alerts do not need to notify an external service. Their purpose is to demonstrate detection and diagnosis locally.

---

# 18. Runbooks

Write at least two useful runbooks.

## `docs/runbooks/pipeline-backlog.md`

It should explain:

1. How to confirm that processing lag is increasing.
2. How to inspect RabbitMQ queue depth.
3. How to distinguish a slow worker from a database problem.
4. How to inspect worker errors using logs and traces.
5. How to restart the worker safely.
6. How to verify that the backlog is draining.
7. How to replay dead-lettered batches.
8. How to confirm that no duplicate samples were created.

## `docs/runbooks/collector-offline.md`

It should explain:

1. How to inspect the latest collector heartbeat.
2. How to check whether the source or gateway connection is failing.
3. How to inspect spool depth and disk utilization.
4. How to restart the collector without resetting its cursor.
5. How to confirm that buffered readings are uploading.
6. How to verify that end-to-end freshness returns to normal.

A runbook should contain commands, expected observations, and recovery verification—not only general advice.

---

# 19. Failure scenarios

The completed system must pass these scenarios:

| Failure                                                        | Expected behavior                                                  |
| -------------------------------------------------------------- | ------------------------------------------------------------------ |
| Gateway stopped for ten minutes                                | Collector buffers approximately 4,320 observations locally         |
| Collector restarted during gateway outage                      | Cursor, batches, and unsent observations survive                   |
| Gateway restarted after receiving a batch but before returning | Collector retries; gateway recognizes the same batch ID            |
| Same batch uploaded twice                                      | Only one batch manifest and one set of canonical samples           |
| Same queue message delivered twice                             | Worker processes idempotently                                      |
| Worker stopped                                                 | RabbitMQ backlog grows while ingestion remains available           |
| Worker restarted                                               | Backlog drains without duplicate samples                           |
| Database stopped                                               | Worker retries and eventually dead-letters after configured limits |
| MinIO stopped                                                  | Gateway does not acknowledge batches it cannot persist             |
| Invalid unit generated                                         | Observation is rejected with an exact reason                       |
| New unknown source tag generated                               | Observation is retained raw and rejected                           |
| New mapping version installed                                  | Historical rejected observations can be replayed                   |
| Same replay executed twice                                     | Canonical sample count does not increase twice                     |
| Out-of-order readings generated                                | Readings remain queryable by event time and receive a flag         |
| Source timestamp in the future                                 | Reading is flagged or rejected according to configuration          |
| Collector spool reaches high-water mark                        | Source polling pauses instead of dropping readings                 |

---

# 20. Local developer workflow

A fresh clone should be usable with:

```bash
git clone <repository-url>
cd industrial-telemetry-lab

cp .env.example .env

docker compose --profile observability up --build
```

Suggested commands:

```bash
./mvnw test
./mvnw verify

./scripts/verify-local.sh
./scripts/run-end-to-end-tests.sh
./scripts/run-demo.sh

docker compose logs -f edge-collector
docker compose logs -f telemetry-gateway
docker compose logs -f telemetry-worker

docker compose down
docker compose down -v
```

Suggested local endpoints:

```text
Telemetry API:       http://localhost:8080
Swagger UI:          http://localhost:8080/swagger-ui.html
Grafana:             http://localhost:3000
Prometheus:          http://localhost:9090
RabbitMQ management: http://localhost:15672
MinIO console:       http://localhost:9001
```

All default credentials must be clearly marked as local-development-only and stored in `.env.example`, never as real secrets.

---

# 21. Testing without CI/CD

There will be no GitHub Actions workflow. Instead, make local verification reproducible.

## Unit tests

Cover:

* Source-tag parsing.
* Mapping rules.
* Unit conversion.
* Data-quality rules.
* Deterministic observation IDs.
* Batch checksum generation.
* Retry timing.
* High-water and low-water spool behavior.
* Contract-version dispatching.

## Contract tests

Validate:

* Valid batch examples against JSON Schema.
* Missing required fields.
* Unsupported versions.
* Additional optional fields.
* Wrong types.
* Invalid timestamps.
* Batch size limits.

## Integration tests

Use Testcontainers for:

* PostgreSQL or TimescaleDB.
* RabbitMQ.
* MinIO.

Test:

* Gateway raw persistence.
* Outbox publication.
* Worker consumption.
* Database failure and retry.
* Duplicate queue messages.
* Replay.
* Collector restart with a persisted SQLite file.

## End-to-end scenario tests

Create scripts for:

```text
normal-operation
gateway-outage
collector-restart
worker-backlog
database-outage
duplicate-delivery
invalid-unit
unknown-tag-and-replay
```

Each script should print explicit assertions, for example:

```text
Expected source observations: 4320
Raw observations persisted:   4320
Canonical samples written:    4320
Duplicate samples written:    0
Result: PASS
```

## Local quality command

Provide one root command:

```bash
./scripts/verify-local.sh
```

It should run:

1. Formatting or style checks.
2. Unit tests.
3. Contract validation.
4. Integration tests.
5. Docker Compose configuration validation.

Do not add `.github/workflows`.

---

# 22. Repository structure

```text
industrial-telemetry-lab/
├── README.md
├── LICENSE
├── SECURITY.md
├── CONTRIBUTING.md
├── .env.example
├── .gitignore
├── docker-compose.yml
├── pom.xml
├── mvnw
├── mvnw.cmd
│
├── contracts/
│   ├── raw-observation.batch.v1.schema.json
│   ├── telemetry.sample.v1.schema.json
│   ├── examples/
│   │   ├── valid-raw-batch.json
│   │   ├── invalid-unit-batch.json
│   │   └── unknown-tag-batch.json
│   └── signal-registry.yaml
│
├── config/
│   ├── mappings/
│   │   ├── controller-a-mapping-1.0.0.yaml
│   │   └── controller-a-mapping-1.1.0.yaml
│   └── quality-rules/
│       └── quality-rules-1.0.0.yaml
│
├── controller-simulator/
│   ├── pom.xml
│   └── src/
│
├── edge-collector/
│   ├── pom.xml
│   └── src/
│
├── telemetry-platform/
│   ├── pom.xml
│   └── src/
│
├── database/
│   └── migrations/
│
├── observability/
│   ├── prometheus/
│   │   ├── prometheus.yml
│   │   └── alert-rules.yml
│   ├── grafana/
│   │   ├── provisioning/
│   │   └── dashboards/
│   ├── tempo/
│   ├── loki/
│   └── otel-collector/
│
├── scripts/
│   ├── verify-local.sh
│   ├── run-end-to-end-tests.sh
│   ├── run-demo.sh
│   └── scenarios/
│       ├── gateway-outage.sh
│       ├── collector-restart.sh
│       ├── worker-backlog.sh
│       ├── database-outage.sh
│       ├── duplicate-delivery.sh
│       └── unknown-tag-and-replay.sh
│
├── docs/
│   ├── architecture.md
│   ├── data-contracts.md
│   ├── failure-semantics.md
│   ├── public-repository-notes.md
│   ├── runbooks/
│   │   ├── pipeline-backlog.md
│   │   └── collector-offline.md
│   └── adrs/
│       ├── 001-at-least-once-delivery.md
│       ├── 002-raw-observations-as-source-of-truth.md
│       ├── 003-event-time-vs-processing-time.md
│       ├── 004-deterministic-observation-identities.md
│       ├── 005-transactional-outbox.md
│       ├── 006-versioned-mapping-files.md
│       └── 007-future-collector-rollouts.md
│
└── sample-data/
```

---

# 23. Architecture decision records

The ADRs are important because they demonstrate senior-level reasoning.

## ADR 001: At-least-once delivery

Explain:

* Why retries can create duplicate deliveries.
* Why exactly-once delivery is not claimed.
* How deterministic IDs and database constraints create idempotent outcomes.

## ADR 002: Raw observations as the source of truth

Explain:

* Why the exact original batch is retained.
* Why processed telemetry is treated as a projection.
* How raw storage enables replay after mapping or validation changes.

## ADR 003: Event time versus processing time

Explain:

* Why queries use `observedAt`.
* Why freshness uses observed-to-processed lag.
* How out-of-order arrivals are handled.

## ADR 004: Deterministic observation identities

Explain:

* Why random IDs are insufficient for replay.
* How source epoch, sequence, and tag form identity.
* What happens after a source sequence reset.

## ADR 005: Transactional outbox

Explain:

* The failure window between database writes and queue publication.
* Why the outbox is persisted transactionally.
* How unpublished events are retried.

## ADR 006: Versioned mapping files

Explain:

* Why source-specific tags do not appear in downstream APIs.
* Why existing mapping versions are immutable.
* How reprocessing records the selected mapping version.

## ADR 007: Future collector rollouts

Keep this as design-only documentation. Describe how software or configuration rollout rings could work, but do not implement them in the initial project.

---

# 24. Implementation sequence

## Phase 1: Repository foundation

Create:

* Maven multi-module structure.
* Docker Compose dependencies.
* Neutral project naming.
* JSON schemas.
* Signal registry.
* Initial ADRs.
* Database migrations.

Exit condition:

```text
Docker Compose starts PostgreSQL/TimescaleDB, RabbitMQ, and MinIO.
Schemas and example payloads validate locally.
```

## Phase 2: Simulator

Implement:

* Twelve zones.
* Three signals per zone.
* Five-second production interval.
* Cursor-based API.
* Source epoch and sequence.
* Fault injection.
* Health and metrics endpoints.

Exit condition:

```text
The simulator generates stable readings and can deliberately produce duplicates, disorder, invalid units, and delays.
```

## Phase 3: Collector durability

Implement:

* HTTP source adapter.
* SQLite spool.
* Atomic cursor advancement.
* Persisted outbound batches.
* Upload retry.
* Backpressure.
* Heartbeat.

Exit condition:

```text
Stopping the gateway causes the spool to grow.
Restarting the collector does not lose the cursor or unsent data.
```

## Phase 4: Raw-first gateway

Implement:

* Batch ingestion endpoint.
* Payload limits.
* Checksums.
* MinIO persistence.
* Batch manifests.
* Duplicate-batch handling.
* Transactional outbox.
* RabbitMQ publication.

Exit condition:

```text
An acknowledged batch always has a raw object and manifest.
Retrying the same batch does not create another object or manifest.
```

## Phase 5: Worker and time-series storage

Implement:

* Queue consumer.
* Raw object loading.
* Mapping.
* Validation.
* Quality flags.
* Deterministic observation IDs.
* Deduplication.
* TimescaleDB writes.
* Rejection recording.
* Retry and dead-letter behavior.

Exit condition:

```text
Valid observations are queryable.
Invalid observations are inspectable.
Duplicate queue delivery creates no duplicate samples.
```

## Phase 6: Query and replay

Implement:

* Signal registry API.
* Telemetry query API.
* Batch inspection API.
* Collector inventory API.
* Replay creation and status APIs.
* Reprocessing with a selected mapping version.

Exit condition:

```text
Previously rejected unknown tags become valid after adding a mapping and replaying the affected interval.
Repeating the replay does not increase the sample count.
```

## Phase 7: Observability and incident readiness

Implement:

* Metrics.
* Structured logs.
* Distributed traces.
* Dashboards.
* Alert rules.
* Runbooks.

Exit condition:

```text
A gateway outage, worker outage, and database outage are visible through metrics, logs, and traces, and each has a documented recovery path.
```

## Phase 8: Public repository polish

Complete:

* README.
* Architecture diagram.
* API examples.
* Screenshots.
* Demo script.
* Local verification script.
* Limitations and trade-offs.
* License.
* Public-content audit.

Exit condition:

```text
A person with Docker and Java can clone the repository, start it locally, run the demonstration, and understand the design without external context.
```

---

# 25. Final demonstration

A strong demonstration should follow this exact story.

## 1. Start normal processing

Start all services and show:

* Twelve zones reporting.
* Worker processing lag below the SLO threshold.
* Empty collector spool.
* Empty RabbitMQ backlog.
* Query results for `zone-07`.

Example:

```http
GET /api/v1/telemetry
    ?facilityId=facility-alpha
    &assetId=zone-07
    &signalId=environment.air_temperature
    &from=<ten-minutes-ago>
    &to=<now>
```

## 2. Create a gateway outage

Stop the gateway:

```bash
docker compose stop telemetry-gateway
```

Show:

* Collector upload failures.
* SQLite spool growth.
* Oldest unsent observation age increasing.
* Collector continuing to operate until the configured high-water mark.
* No readings silently discarded.

## 3. Restart the collector during the outage

```bash
docker compose restart edge-collector
```

Show:

* Existing spool still present.
* Cursor still present.
* No reset to the start of the source history.
* No disappearance of pending batches.

## 4. Restore the gateway

```bash
docker compose start telemetry-gateway
```

Show:

* Collector retries existing batch IDs.
* Spool drains.
* Queue briefly grows.
* Worker catches up.
* End-to-end lag returns below the target.
* Expected and stored counts match.

## 5. Create duplicate and out-of-order data

Enable simulator faults.

Show:

* Duplicate counters increasing.
* No duplicate canonical rows.
* Out-of-order observations remaining queryable by `observedAt`.
* Out-of-order flags visible in API results.

## 6. Stop the worker

```bash
docker compose stop telemetry-worker
```

Show:

* Gateway still accepts and durably stores raw batches.
* RabbitMQ backlog grows.
* Processing-lag alert fires.
* Collector does not need to know that the worker is down.

Restart the worker and show the backlog draining.

## 7. Introduce an unknown tag

Enable a new simulator tag that is not in mapping version 1.0.0.

Show:

* Raw batches still accepted.
* Observations rejected with `UNKNOWN_SOURCE_TAG`.
* Rejections visible in Grafana and through the batch-inspection API.
* Exact original source observations still present in MinIO.

## 8. Add mapping version 1.1.0

Add a new mapping rule and restart the worker with the updated mapping set.

Create a replay for the affected time range.

Show:

* Previously rejected observations become canonical samples.
* Replay records the mapping version.
* Running the same replay again produces duplicates as processing outcomes but no additional time-series rows.

## 9. Inspect an end-to-end trace

Choose one batch and show:

```text
Collector upload
→ gateway validation
→ raw object write
→ manifest transaction
→ outbox publication
→ queue consumption
→ mapping
→ time-series write
```

## 10. Use the runbook

Walk through `pipeline-backlog.md` as though responding to an incident.

This proves operational ownership rather than only code completion.

---

# 26. Definition of done

The core project is complete when all of the following are true:

* A fresh clone starts through Docker Compose.
* No cloud account or cloud credentials are required.
* No Terraform exists in the repository.
* No CI/CD workflows exist in the repository.
* The source simulator generates twelve zones and three signals per zone.
* The collector durably persists observations before advancing its cursor.
* The collector survives restarts with unsent data intact.
* An unavailable gateway causes buffering rather than silent loss.
* Every acknowledged batch exists in raw object storage and in the batch manifest.
* Duplicate batch upload is idempotent.
* Duplicate queue delivery is idempotent.
* Valid samples are queryable by event time.
* Invalid samples have precise rejection reasons.
* Raw input remains available after rejection.
* A new mapping version can be used to replay previously rejected observations.
* Replaying the same interval twice does not duplicate canonical rows.
* Logs, metrics, and traces cover the complete pipeline.
* At least two operational runbooks are included.
* The public README explains the architecture, failure semantics, trade-offs, and local setup.
* The repository contains only fictional, generic names and synthetic data.
* The repository contains no employer, product, job-posting, location, or application references.

---

# 27. Public GitHub presentation

The repository should open with something close to:

> Industrial Telemetry Lab is a fully local platform for ingesting telemetry from a fictional industrial control system. It focuses on durable edge buffering, raw-data retention, at-least-once delivery, idempotent processing, time-series storage, replay, data-quality enforcement, and operational observability.
>
> The entire environment runs through Docker Compose. All systems, identifiers, and data are fictional.

The README should contain:

1. Problem statement.
2. Architecture diagram.
3. Quick start.
4. Demonstration walkthrough.
5. Reliability guarantees.
6. Failure semantics.
7. Data-contract examples.
8. API examples.
9. Dashboard screenshots.
10. Testing commands.
11. Design trade-offs.
12. Known limitations.

Avoid:

* Explaining that the project was built for a particular application.
* Copying wording from a job advertisement.
* Mentioning an employer or employer product.
* Using employer-inspired colors, icons, diagrams, or asset names.
* Implying access to any internal architecture.
* Calling the system production-ready.
* Including real plant, customer, machinery, or operational data.

A suitable repository disclaimer is:

> This is an independent educational project. The facility, systems, devices, telemetry, and operational scenarios are entirely fictional.

This amended project remains compact: **one source, one collector, twelve zones, three signals, one local platform, one replay path, and one observability stack**. At the same time, it gives you direct practice with the difficult parts of industrial telemetry engineering: acknowledgment boundaries, durable buffering, backpressure, versioned contracts, data quality, idempotency, event time, replay, incident diagnosis, and recovery.

---

# 28. Pre-development addendum

This addendum was prepared on 2026-08-29 after reviewing the complete plan, the development machine, the current upstream projects, and the failure semantics. Sections 1 through 27 above are preserved as the original plan. This addendum extends them; it does not silently replace them. If a later decision changes a provisional item below, record that decision in an ADR before implementation.

## 28.1 Hard constraints

The implementation must preserve all of these constraints:

* The complete runtime, demonstration, tests, dashboards, logs, metrics, traces, queue, object store, and databases run on one local development machine.
* Starting the project may download Maven artifacts and container images, but the running system must not require a cloud account, hosted API, external data source, or outbound application integration.
* The repository is public.
* No CI/CD workflow is added. In particular, do not create .github/workflows.
* No infrastructure-as-code for a cloud provider is added.
* All host-facing ports bind to 127.0.0.1 unless a documented demonstration explicitly requires otherwise.
* All credentials are synthetic local-development credentials. The real .env file is ignored.
* No real facility, customer, device, production measurement, or employer material is used.
* The implementation must remain honest about its guarantees and must not call itself production-ready.

## 28.2 Neutral-domain boundary

To make the project visibly independent from the excluded company and its business domain, the fictional setting should be framed as an **environmental qualification lab for empty equipment enclosures**. Its twelve zones are generic test chambers that exercise temperature, humidity, and ventilation controls. The telemetry path is the subject of the project; the chambers and measurements are synthetic scenery.

Do not introduce agriculture, biology, insects, livestock, food or feed processing, waste conversion, fertilizer, circular-economy processes, modular farming facilities, or related imagery. Do not use names, slogans, visual identity, product terminology, or diagrams associated with the excluded company. Keep the existing neutral identifiers such as facility-alpha, zone-07, controller-a, and Industrial Telemetry Lab unless a later public-content audit finds a specific conflict.

The final public-content audit must be performed against an external review checklist so that excluded company names and product names do not need to be committed to this repository.

---

# 29. Development-machine readiness

The machine was inspected on 2026-08-29.

| Requirement | Observed state | Action before development |
| --- | --- | --- |
| Operating system | Windows 11 Pro with WSL2 Ubuntu | None |
| CPU and memory | 20 logical processors and 31.6 GiB host memory | Sufficient |
| Docker allocation | Linux engine, 20 CPUs, about 15.4 GiB memory | Sufficient for the full profile |
| Free system-disk space | About 40.5 GiB, with substantial Docker cache reclaimable if ever needed | Add a non-destructive disk-space warning to the preflight script |
| Java | Eclipse Temurin Java 21.0.10 LTS, JAVA_HOME configured | None |
| Git | Installed and identity configured | None |
| GitHub CLI | Installed and authenticated with repository scope | Sufficient to create and push the public repository |
| Docker | Docker Desktop engine is running | None |
| Docker Compose | Compose 5.4.0 | None |
| Bash | WSL2 GNU Bash 5.2 | Available |
| PowerShell | Native Windows shell | Available |
| jq, curl, OpenSSL | Installed | Available for local scripts |
| Planned ports | All planned ports were free during inspection | Recheck on every startup |
| Standalone Maven | Not installed | Do not install it; commit and use the Maven Wrapper |

No host dependency needs to be installed before the end-to-end development goal starts. PostgreSQL, TimescaleDB, RabbitMQ, the object store, Prometheus, Grafana, Loki, Tempo, Alloy, and the OpenTelemetry Collector must remain containerized.

Add scripts/check-prerequisites.ps1 and scripts/check-prerequisites.sh. They should verify Java 21, Docker daemon access, Compose availability, free ports, and at least 15 GiB of free disk space. They should report corrective instructions without modifying the host.

---

# 30. Research snapshot and provisional version baseline

This is a research snapshot, not permission to use floating tags. Revalidate it once at the beginning of implementation, then pin every direct Maven dependency, Maven plugin, base image, and service image. Container images should use an explicit version and immutable digest. Record the chosen versions and licenses in docs/dependency-baseline.md.

| Concern | Provisional baseline on 2026-08-29 | Planning result |
| --- | --- | --- |
| Java | Temurin 21 LTS | Keep Java 21 as planned |
| Application framework | Spring Boot 4.1.1 | Compatible with Java 21; use the current stable major |
| API documentation | springdoc-openapi 3.1.0 | Supports Spring Boot 4 |
| Build bootstrap | Maven Wrapper 3.3.4 running Maven 3.9.16 | No global Maven installation |
| Database migration | Flyway 13.4.0 | Use for PostgreSQL and SQLite migrations |
| SQLite driver | Xerial sqlite-jdbc 3.53.4.0 | Current Java 21-compatible baseline |
| Time-series database | TimescaleDB 2.29.2 on PostgreSQL 17 | Use a lightweight single-node image; verify the exact image tag and digest |
| Message broker | RabbitMQ 4.3.5 management image | Use durable quorum queues, publisher confirms, and manual consumer acknowledgements |
| Integration tests | Testcontainers for Java 2.0.5 | Docker Desktop on Windows is supported |
| Metrics | Prometheus 3.14.0 and Micrometer managed by Spring Boot | Prometheus scrapes application endpoints |
| Dashboards | Grafana 13.2.0 | Provision data sources and dashboards from versioned files |
| Logs | Loki 3.7.7 plus Grafana Alloy 1.19.2 | Alloy replaces obsolete log-shipping approaches |
| Traces | Tempo 3.0.3 and OpenTelemetry Collector 0.159.0 | Export OTLP locally |
| Java tracing | OpenTelemetry Java agent | Default zero-code instrumentation, extended with explicit spans only where useful |
| Raw object storage | Decision required | The originally planned MinIO community repository is archived and unmaintained |

## 30.1 Raw object-store research result

The MinIO community repository was archived in April 2026 and now states that it is no longer maintained. Its last community server release was in October 2025, the community distribution became source-only, and its licensing requires an explicit compliance review. A new public portfolio project should not quietly depend on an archived server.

The recommended replacement is **SeaweedFS 4.44 in single-node S3 mode**:

* It is actively maintained.
* It exposes the small S3 subset required by RawObjectStore.
* It has an Apache-2.0 license.
* It has an official container workflow.
* Application code remains backend-neutral by using the AWS SDK S3 client behind RawObjectStore.

Keep MinIO only if there is an explicit decision to accept its archived status, build the server from source, and document the licensing and maintenance trade-off. Do not use an unofficial third-party MinIO binary image.

---

# 31. Architecture clarifications

## 31.1 Exact retry bytes and the gateway acknowledgment boundary

The collector must serialize and gzip a batch once, then persist the exact compressed byte array and its SHA-256 digest before the first upload. Every retry sends those same bytes. Reconstructing JSON or gzip on each attempt is prohibited because property ordering, numeric formatting, or gzip headers could change the checksum.

Use the standard Content-Digest request header with SHA-256 over the exact compressed HTTP content. Persist the same digest in outbound_batch and ingestion_batch.

The gateway flow is:

1. Stream the compressed request into a bounded temporary buffer while calculating its digest.
2. Verify Content-Digest before parsing.
3. Decompress through a bounded stream and validate the envelope.
4. Begin a PostgreSQL transaction and acquire a transaction-scoped advisory lock derived from the batch ID.
5. Recheck ingestion_batch after acquiring the lock.
6. Put the raw object at its deterministic key with put-if-absent semantics.
7. If the object already exists, compare its stored SHA-256 metadata with the request digest. Never overwrite a different payload.
8. Insert the manifest and outbox event in one database transaction.
9. Commit, then return success.

The raw object key partitions by gateway receipt time, not by an untrusted source timestamp.

A crash after the object write but before the database commit can leave an orphan object. A retry of the same batch repairs that state by recognizing the existing object and completing the manifest transaction. Add a reconciliation command that reports orphan objects and missing objects. It may delete only confirmed temporary orphans when explicitly requested; normal startup must not delete data.

The gateway returns success only when the raw object exists with the expected digest and the manifest plus outbox transaction has committed. Concurrent uploads of the same batch ID with different bytes must deterministically return 409 for the loser and must never overwrite the first raw object.

## 31.2 Bounded ingestion

Set explicit initial limits and make them configurable:

| Limit | Initial value |
| --- | --- |
| Observations per batch | 500 |
| Compressed request body | 1 MiB |
| Decompressed request body | 10 MiB |
| Collector batch maximum age | 5 seconds |
| Gateway request timeout | 30 seconds |
| Maximum source-tag length | 256 characters |
| Maximum collector and facility identifier length | 64 characters |

Enforce both compressed and decompressed limits to prevent oversized payloads and gzip expansion attacks. The worker must also validate observation count and field lengths even though the gateway performs envelope validation.

## 31.3 Global deduplication with a TimescaleDB hypertable

TimescaleDB requires every unique index on a hypertable to include its time-partitioning column. Therefore a unique index on observation_id alone is not a valid global uniqueness mechanism.

Add an ordinary PostgreSQL table named telemetry_sample_identity:

| Column | Purpose |
| --- | --- |
| observation_id | Primary key and global identity |
| observed_at | Must match the hypertable row |
| raw_batch_id | First batch that produced the sample |
| created_at | Audit timestamp |

In the same worker transaction:

1. Insert telemetry_sample_identity with ON CONFLICT DO NOTHING.
2. If the identity was inserted, insert the canonical hypertable row.
3. If it already existed, compare observed_at and record a duplicate or identity-conflict outcome.
4. Update the processing attempt and batch counters.
5. Commit before acknowledging RabbitMQ.

The telemetry_sample hypertable can then use a composite unique index on observation_id and observed_at, while telemetry_sample_identity enforces global uniqueness. A rollback removes both inserts.

Do not put rejected observations in telemetry_sample_identity, because a later mapping version must be able to turn a previous rejection into a canonical sample. Make rejection writes idempotent for the same batch, mapping version, rules version, and replay context.

Use a one-day initial chunk interval and an index beginning with facility_id, asset_id, signal_id, and observed_at descending. Compression, continuous aggregates, and automatic retention remain out of the first implementation unless measurements justify them.

## 31.4 Transactional outbox details

Outbox workers should claim rows with SELECT FOR UPDATE SKIP LOCKED. Publish persistent reference messages with the mandatory flag and publisher confirms. Mark an outbox row published only after a positive broker confirm.

A crash after the broker confirm but before published_at is committed can publish the same reference again. That duplicate is expected and is handled by worker idempotency. The project must describe this window rather than claiming exactly-once publication.

Store trace context with the outbox record and inject it into RabbitMQ headers so that the asynchronous publish and consume spans remain connected.

## 31.5 RabbitMQ topology and safety

The original topology lists two retry queues but describes three retry delays. Add:

text
telemetry.retry.long

Use the explicit short, medium, and long retry queues in the first implementation because they are easy to inspect during the demo. Do not combine them with a second hidden retry mechanism.

Use durable quorum queues even on the one-node local broker. Configure:

* Persistent messages.
* Publisher confirms.
* Manual consumer acknowledgment.
* At-least-once dead lettering.
* overflow set to reject-publish.
* Finite queue byte limits.
* A delivery limit of five.
* A dead-letter queue with no automatic expiry.

The single-node deployment demonstrates message semantics, not broker high availability. State this limitation prominently.

## 31.6 SQLite collector durability

Apply the collector schema with Flyway and configure SQLite with:

text
PRAGMA journal_mode=WAL;
PRAGMA synchronous=FULL;
PRAGMA foreign_keys=ON;
PRAGMA busy_timeout=5000;

Use one serialized writer path. Keep transactions short and retry SQLITE_BUSY with a bounded policy. The SQLite database, WAL file, and shared-memory file must live together on one Docker named volume; do not place the live spool on a network filesystem.

Extend outbound_batch with the exact compressed payload blob, SHA-256 digest, observation count, created time, state, attempt count, and next-attempt time. Use a persisted state machine such as READY, UPLOADING, ACKNOWLEDGED, and FAILED. A process crash in UPLOADING must safely return the batch to retry without changing its ID or bytes.

At 7.2 observations per second, 100,000 rows hold about 3 hours 51 minutes of readings. The 80 percent high-water mark is reached after about 3 hours 5 minutes from empty. Expose these capacities in the README and dashboard rather than implying indefinite buffering.

## 31.7 Simulator cursor and history gaps

Keep a bounded in-memory history of at least 250,000 observations, which is roughly 9.6 hours at the planned rate. A simulator restart creates a new sourceEpoch and restarts its sequence. The collector must retain old-epoch spooled observations while beginning a cursor for the new epoch.

If a collector cursor is older than retained history, the simulator returns 410 Gone with sourceEpoch, earliestSequence, and latestSequence. The collector must stop advancing that source, emit a data-gap metric and structured error, and require an explicit recovery action. Silently jumping to the newest cursor is prohibited.

Use a seeded random generator and an injectable UTC Clock so normal and faulted test runs are reproducible.

## 31.8 Replay isolation

The ingestion_batch query selects batches whose minimum and maximum observed times overlap the requested range. The worker still filters individual observations to the exact replay interval.

Add a unique replay_batch key on replay_id and batch_id. Allow only one active replay by default, cap an initial replay request at 24 hours, and rate-limit replay work so live ingestion is not starved. Replay messages carry their selected mapping and quality-rules versions; they do not depend on an unspecified current version.

Live and replay processing call the same pure mapping, validation, identity, and persistence services. Only the processing context differs.

## 31.9 REST conventions

Use RFC 9457 application/problem+json responses for API errors. Return stable machine-readable reason codes without exposing stack traces.

Telemetry queries use keyset pagination ordered by observed_at and observation_id, not offset pagination. Start with:

| Setting | Initial value |
| --- | --- |
| Maximum query range | 24 hours |
| Default page size | 200 |
| Maximum page size | 1,000 |
| Time format | UTC ISO-8601 |

Generate OpenAPI at runtime and export a checked-in OpenAPI snapshot during local verification. Contract tests must fail when the implementation and snapshot diverge unexpectedly.

The local bearer token protects ingestion, replay, fault-injection, and other modifying endpoints. Read-only telemetry and documentation endpoints may remain locally accessible for the demo. Never log Authorization, cookies, complete raw batches, or local credentials.

## 31.10 Observability implementation

Use:

* Micrometer and the Prometheus actuator endpoint for metrics.
* The OpenTelemetry Java agent for HTTP, JDBC, and messaging traces.
* Explicit spans for SQLite durability, raw-object persistence, mapping, replay selection, and outbox claiming where automatic instrumentation is insufficient.
* W3C trace context through HTTP, the outbox, and RabbitMQ.
* JSON logs to stdout and bounded rolling files.
* Grafana Alloy to read the shared application-log volume and send logs to Loki.
* The OpenTelemetry Collector to send traces to Tempo.

Do not mount the Docker daemon socket merely to collect logs. Use a read-only shared log volume for Alloy instead.

Metric labels must come from bounded sets. Do not use sourceTag, batchId, observationId, exception text, or replayId as metric labels. They belong in logs and traces.

Configure histogram buckets around the 30-second freshness objective and add recording rules for the good-quality sample latency distribution. Future timestamps and intentionally bad source quality must not corrupt the SLO calculation.

## 31.11 Docker Compose and local security

Core services remain outside profiles. Grafana, Prometheus, Loki, Tempo, Alloy, and the OpenTelemetry Collector use the observability profile.

Add health checks for every dependency. Use service_healthy and service_completed_successfully for startup ordering, but keep application-level connection retry because Compose ordering is not a runtime availability guarantee.

Run Flyway through a one-shot database-migrate service before gateway and worker startup. The worker validates the schema version but does not race to migrate it.

Use named volumes for durable state. Bind admin consoles and data ports to loopback only. Run containers as non-root and with a read-only root filesystem where their images and runtime needs permit it.

Provide a normal stop command that preserves volumes and an explicitly destructive reset-local-data command that removes only this Compose project's named volumes after printing the exact targets.

---

# 32. Repository-structure additions

Extend the proposed structure with:

text
.mvn/wrapper/
telemetry-contracts/
observability/alloy/
scripts/check-prerequisites.sh
scripts/check-prerequisites.ps1
scripts/verify-local.ps1
scripts/run-end-to-end-tests.ps1
scripts/run-demo.ps1
scripts/scenarios/*.ps1
docs/dependency-baseline.md
docs/demo/
docs/adrs/008-concurrent-batch-receipt.md
docs/adrs/009-global-observation-deduplication.md
docs/adrs/010-local-object-store-selection.md
docs/adrs/011-local-security-boundary.md

telemetry-contracts is a small Java module containing contract DTOs, schema loading, deterministic serialization, digest utilities, and shared reason-code enums. It must not contain service logic, database entities, or vendor clients.

Keep shell and PowerShell scenario scripts behaviorally equivalent. The Windows machine can run Bash through WSL2, but native PowerShell entry points make the public quick start genuinely cross-platform.

---

# 33. Local quality and verification gates

No CI service will run these gates. The repository must make them repeatable and must show the last verified commands and environment in the README.

| Gate | Purpose |
| --- | --- |
| mvnw.cmd test or ./mvnw test | Fast unit and contract tests |
| mvnw.cmd verify or ./mvnw verify | Formatting, static analysis, coverage, unit, contract, and integration tests |
| scripts/verify-local.ps1 or scripts/verify-local.sh | Full repository and Compose validation |
| scripts/run-end-to-end-tests.ps1 or .sh | Failure-scenario suite against the local stack |
| scripts/run-demo.ps1 or .sh | Guided portfolio demonstration |

Use Spotless for deterministic formatting, compiler warnings as errors where practical, SpotBugs for bug patterns, and JaCoCo for coverage evidence. Favor meaningful package-level coverage thresholds over a misleading 100 percent target.

The full verification suite must include these additional cases:

* Two concurrent uploads with the same batch ID and same bytes.
* Two concurrent uploads with the same batch ID and different bytes.
* Gateway crash after raw write but before manifest commit.
* Outbox crash after broker confirmation but before published_at.
* Exact collector batch bytes remain unchanged across retry and restart.
* SQLite database plus WAL recovery after an abrupt collector stop.
* TimescaleDB global duplicate prevention across different chunks.
* Duplicate rejection delivery does not multiply the same rejection audit row.
* Cursor-expired response produces a visible gap state and no silent skip.
* Replay and live processing compete without starving live ingestion.
* Gzip decompression limit is enforced.
* Authorization and other secrets are absent from logs.
* All public ports bind only to loopback.

Use Awaitility or bounded polling for asynchronous assertions. Avoid fixed long sleeps. Every scenario must have a timeout, print observed evidence, and clean up only the state it created.

Create a local dependency and image audit command that emits an SBOM and reports known issues. Because there is no CI/CD, security and dependency review is a documented manual release step rather than an automated promise.

---

# 34. Portfolio-ready README requirements

The final README is a deliverable, not cleanup after the code is finished. It must let a reviewer understand the project in roughly one minute and run the main demonstration without reading the source.

Lead with:

1. A two-paragraph independent project summary.
2. A compact architecture diagram.
3. A table of reliability guarantees and explicit non-guarantees.
4. A tested quick start for PowerShell and Bash.
5. A five-to-ten-minute demonstration path.

Also include:

* Prerequisites and realistic CPU, memory, disk, and first-pull expectations.
* The acknowledgment boundary in one diagram.
* Why delivery is at least once and outcomes are idempotent.
* The exact behavior of each planned failure scenario.
* Screenshots of Pipeline Overview, Collector Health, Data Quality, a complete trace, a raw object, and a passing scenario.
* Copy-paste API examples and representative responses.
* A component/version table linked to docs/dependency-baseline.md.
* A concise local security model.
* Reset and cleanup instructions that distinguish preserving and deleting data.
* Known limitations, including one-node services, synthetic authentication, no high availability, no cloud deployment, and no CI/CD.
* The independent educational-project disclaimer already specified above.

Do not use CI badges, employer-inspired visuals, stock industrial imagery, or claims unsupported by an executable scenario. Prefer repository-owned Mermaid source plus an exported high-resolution diagram, accessible alt text, and a restrained neutral color palette.

Capture README screenshots only after the demo scripts and dashboards are stable. Seeded simulator data should make those screenshots reproducible.

---

# 35. Development-launch checklist

Before launching the end-to-end development goal:

1. Resolve the three open decisions in section 36.
2. Revalidate the provisional dependency versions and image availability once.
3. Record image tags, digests, licenses, and source links.
4. Confirm the public repository remote and default branch.
5. Add the Maven Wrapper and verify it on PowerShell and Bash.
6. Add .gitignore before creating any local .env, database, log, trace, or object-store files.
7. Create the first ADRs and contract schemas before service code.
8. Keep each implementation phase independently buildable and locally verifiable.
9. Commit at meaningful phase boundaries so the public history tells a coherent engineering story.
10. Run the neutral naming and public-content audit before every public-polish milestone.

The end-to-end goal should not be marked complete until the original definition of done and every accepted addendum gate are verified from a fresh clone.

---

# 36. Open decisions requiring owner input

## P-01: Maintained local S3-compatible object store

**Recommended:** Replace the archived MinIO runtime with SeaweedFS in single-node S3 mode while keeping the RawObjectStore abstraction and the raw-object behavior unchanged.

**Alternative:** Keep MinIO only by building its final community source release and accepting the archived maintenance and licensing story.

This decision is required before Phase 1 Compose and ADR 010.

## P-02: Repository license

**Recommended:** Apache License 2.0 because it is permissive and includes an explicit patent grant.

**Alternative:** MIT for a shorter, very permissive license.

Until selected, a public repository without a license remains all-rights-reserved by default. Select the license before the first code release.

## P-03: Explicitly neutral fictional setting

**Recommended:** Approve the environmental qualification lab for empty equipment enclosures described in section 28.2. It preserves all twelve zones, signals, tags, APIs, and failure scenarios while making the public narrative clearly unrelated to biological or agricultural operations.

**Alternative:** Keep the more abstract phrase climate-controlled industrial facility, with the same prohibited-domain boundary.

No other owner decision currently blocks development. Framework version, SQL access style, cross-platform scripts, queue type, retry topology, pagination, limits, observability transport, and local security all have recommended defaults above and can be changed later through ADRs.

---

# 37. Research sources

Primary and official sources used for this addendum:

* Spring Boot system requirements: https://docs.spring.io/spring-boot/system-requirements.html
* springdoc-openapi project and releases: https://github.com/springdoc/springdoc-openapi
* Testcontainers supported Docker environments: https://java.testcontainers.org/supported_docker_environment/
* Docker Compose profiles and service dependencies: https://docs.docker.com/compose/how-tos/profiles/ and https://docs.docker.com/reference/compose-file/services/
* TimescaleDB unique indexes on hypertables: https://docs.timescale.com/use-timescale/latest/hypertables/hypertables-and-unique-indexes/
* PostgreSQL transaction-level advisory locks: https://www.postgresql.org/docs/current/functions-admin.html
* RabbitMQ publisher confirms, quorum queues, and dead lettering: https://www.rabbitmq.com/docs/confirms, https://www.rabbitmq.com/docs/quorum-queues, and https://www.rabbitmq.com/docs/dlx
* SQLite write-ahead logging: https://www.sqlite.org/wal.html
* Flyway SQLite support: https://documentation.red-gate.com/flyway/reference/database-driver-reference/sqlite
* OpenTelemetry Java instrumentation: https://opentelemetry.io/docs/zero-code/java/
* Loki native OpenTelemetry ingestion and current log shipping: https://grafana.com/docs/loki/latest/send-data/otel/ and https://grafana.com/docs/loki/latest/send-data/
* HTTP Content-Digest: https://www.rfc-editor.org/rfc/rfc9530.html
* HTTP Problem Details: https://www.rfc-editor.org/rfc/rfc9457.html
* S3 conditional write semantics: https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html
* MinIO community repository status: https://github.com/minio/minio
* SeaweedFS project, S3 mode, releases, and license: https://github.com/seaweedfs/seaweedfs
