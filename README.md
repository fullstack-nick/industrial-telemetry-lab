# Industrial Telemetry Lab

Industrial Telemetry Lab is a fully local, reliability-focused telemetry platform for a fictional environmental qualification lab. A control-system simulator emits measurements from twelve empty equipment-enclosure zones; an edge collector makes those readings durable before acknowledging its source; and a raw-first platform stores, processes, queries, observes, and replays them.

The project concentrates on the failure boundaries that are easy to hide in a happy-path demo: SQLite edge buffering, immutable compressed batches, conditional raw-object writes, a transactional outbox, at-least-once RabbitMQ delivery, globally idempotent processing, event-time queries, versioned mappings, quarantine and replay, and correlated metrics, logs, and traces. Everything runs through Docker Compose without a cloud account.

> This is an independent educational project. The facility, systems, devices, telemetry, and operational scenarios are entirely fictional.

## Architecture at a glance

```mermaid
flowchart LR
  S[Control-system simulator<br/>12 zones · 3 signals] -->|cursor pages| C[Edge collector]
  C -->|durable first| E[(SQLite WAL spool)]
  E -->|exact gzip bytes + digest| G[Telemetry gateway]
  G -->|conditional write| O[(SeaweedFS raw objects)]
  G -->|one transaction| P[(TimescaleDB<br/>manifest + outbox)]
  P --> D[Outbox dispatcher]
  D -->|persistent reference| Q[(RabbitMQ quorum queues)]
  Q --> W[Telemetry worker]
  W -->|read + verify raw object| O
  W -->|identity + canonical + audit| P
  P --> A[REST / OpenAPI]
  C & G & W & S --> V[Prometheus · Loki · Tempo · Grafana]
```

The gateway returns success only after both sides of its durability boundary exist:

```mermaid
sequenceDiagram
  participant C as Edge collector
  participant G as Gateway
  participant O as SeaweedFS
  participant D as TimescaleDB
  C->>G: POST exact gzip batch + Content-Digest
  G->>O: PUT If-None-Match: *
  O-->>G: Object durable (or same digest already exists)
  G->>D: BEGIN manifest + outbox
  D-->>G: COMMIT
  G-->>C: 200 durable acknowledgment
  Note over C: Only now mark the local batch acknowledged
```

See [Architecture](docs/architecture.md) for component boundaries and recovery windows.

## What it guarantees

| Property | Implemented guarantee | Explicit non-guarantee |
| --- | --- | --- |
| Edge acquisition | Reading and cursor are committed together in SQLite WAL before cursor advance | The bounded 100,000-row spool is not indefinite storage |
| Batch retry | A batch ID, gzip payload, SHA-256 digest, and retry state survive process restart | A failed HTTP call does not imply the gateway stored nothing; retry is required |
| Gateway acknowledgment | Raw bytes exist and manifest + outbox are committed before `200` | No distributed transaction spans object storage and PostgreSQL |
| Delivery | Persistent references, confirms, manual acknowledgments, three retry delays, terminal DLQ | Delivery is at least once, not exactly once; one broker is not highly available |
| Processing | A global identity table and one database transaction make repeat delivery idempotent | Different source epochs intentionally represent different observations |
| Rejections | Stable reason codes and audit rows; raw bytes remain replayable | A rejected observation is not put in the identity table |
| Replay | Exact interval filtering, explicit mapping/rules versions, one active run, idempotent repeat | Replay is capped at 24 hours and is not a bulk backfill engine |
| Observability | Six scrape endpoints, structured logs, connected traces, four dashboards, alert rules | The local stack has no external paging or long-term retention |

## Quick start

Prerequisites are Java 21, Docker Desktop with Compose v2, and Git. Allocate about 4 CPU cores, 8 GiB RAM, and 15 GiB free disk; the first image and Maven dependency pull can take several minutes.

PowerShell:

```powershell
git clone https://github.com/fullstack-nick/industrial-telemetry-lab.git
Set-Location industrial-telemetry-lab
Copy-Item .env.example .env
.\scripts\check-prerequisites.ps1
docker compose --profile observability up -d --build
```

Bash, macOS, Linux, or WSL2:

```bash
git clone https://github.com/fullstack-nick/industrial-telemetry-lab.git
cd industrial-telemetry-lab
cp .env.example .env
./scripts/check-prerequisites.sh
docker compose --profile observability up -d --build
```

The credentials in `.env.example` are synthetic local-development defaults. Replace them if the machine is shared. All published ports bind to `127.0.0.1`.

| Surface | Local URL |
| --- | --- |
| Grafana dashboards | <http://localhost:3000/dashboards> |
| Swagger UI | <http://localhost:8080/swagger-ui.html> |
| Telemetry gateway | <http://localhost:8080> |
| Collector status | <http://localhost:8082/collector/v1/status> |
| RabbitMQ management | <http://localhost:15672> |
| SeaweedFS filer | <http://localhost:8888> |
| Prometheus | <http://localhost:9090> |
| Tempo API | <http://localhost:3200> |

### Component baseline

| Layer | Verified version |
| --- | --- |
| Application | Java 21 · Spring Boot 4.1.1 · Maven 3.9.16 |
| Edge durability | SQLite JDBC 3.53.4.0 · Flyway 13.4.0 |
| Platform data | TimescaleDB 2.29.2 / PostgreSQL 17 · SeaweedFS 4.44 |
| Messaging | RabbitMQ 4.3.5 management |
| Observability | Prometheus 3.14.0 · OpenTelemetry Collector 0.159.0 · Tempo 3.0.3 · Loki 3.7.7 · Alloy 1.19.2 · Grafana 13.2.0 |

The full license/source inventory and update procedure are in [Dependency baseline](docs/dependency-baseline.md).

### Local security model

Every published port binds to `127.0.0.1`. Modifying and operational APIs require the synthetic bearer token from the untracked `.env`; read-only telemetry, health, Swagger, and dashboards remain locally accessible for demonstration. Application logs are checked for configured token/password values, containers use non-root/read-only settings where supported, and the observability stack reads a dedicated log volume instead of the Docker socket. There is no TLS, identity provider, per-user authorization, secret manager, or hostile-host boundary—see [Security policy](SECURITY.md).

Normal shutdown preserves every named volume:

```powershell
docker compose --profile observability down
```

Use `scripts/reset-local-data.ps1` or `scripts/reset-local-data.sh --force` only when you intentionally want to remove this Compose project's durable state. The script prints its exact volume targets and requires confirmation.

## Five-minute demonstration

Start the full portfolio stack and seed visible quality outcomes:

```powershell
.\scripts\run-demo.ps1
```

```bash
./scripts/run-demo.sh
```

Then:

1. Open **Pipeline Overview** in Grafana and confirm all six scrape endpoints are healthy: four applications plus RabbitMQ's standard and per-object endpoints.
2. Run `scripts/scenarios/gateway-outage.ps1` (or `.sh`). Watch the SQLite spool grow while source polling continues, then drain after the gateway returns.
3. Run `scripts/scenarios/worker-backlog.ps1`. The gateway remains available while the durable RabbitMQ queue grows and later drains.
4. Run `scripts/scenarios/unknown-tag-and-replay.ps1`. Mapping 1.0 quarantines the auxiliary tag; replay with mapping 1.1 recovers it; the repeat replay adds no canonical rows.
5. Open a Tempo trace and follow collector upload → raw-object persistence → outbox → RabbitMQ → worker → TimescaleDB.

The complete guided path, expected evidence, and cleanup are in [Demo guide](docs/demo.md).

## Demo evidence

| View | Evidence |
| --- | --- |
| Pipeline overview | ![Grafana Pipeline Overview dashboard showing healthy ingestion and processing](docs/demo/screenshots/pipeline-overview.png) |
| Collector durability | ![Grafana Collector Health dashboard showing source state and SQLite spool](docs/demo/screenshots/collector-health.png) |
| Data quality | ![Grafana Data Quality dashboard showing accepted, flagged, rejected, and duplicate outcomes](docs/demo/screenshots/data-quality.png) |
| Connected trace | ![Tempo trace spanning collector, gateway, broker, worker, object store, and database](docs/demo/screenshots/complete-trace.png) |
| Immutable raw input | ![SeaweedFS filer view containing partitioned gzip raw-observation objects](docs/demo/screenshots/raw-object.png) |
| Executable recovery proof | ![Passing local failure-scenario assertions](docs/demo/screenshots/passing-scenario.png) |

## Failure behavior

The repository includes behaviorally equivalent PowerShell and Bash scenarios:

| Scenario | Executable proof |
| --- | --- |
| Normal operation | Spool drains, canonical rows grow, manifests reconcile with raw objects, and every raw observation has an outcome |
| Ingestion boundaries | Concurrent identical uploads converge; different bytes conflict; orphan and outbox confirm/commit crash windows repair idempotently |
| Gateway outage | Collector keeps polling and buffers exact batches; recovery drains the spool |
| Abrupt collector stop | Source epoch/cursor, SQLite WAL state, and unsent rows survive a forced process termination |
| Worker backlog | Ingestion continues, RabbitMQ grows, and recovery drains without duplicate identities |
| Database outage | Edge acquisition continues independently and the platform reconnects |
| Duplicate + late delivery | Duplicate outcomes rise, identity uniqueness holds, and late event timestamps receive `OUT_OF_ORDER` |
| Invalid unit | Raw data remains durable and rejection reason is `UNSUPPORTED_RAW_UNIT` |
| Unknown tag + replay | `UNKNOWN_SOURCE_TAG` is recovered with mapping 1.1; repeating replay is idempotent |

Run all nine:

```powershell
.\scripts\run-end-to-end-tests.ps1
```

```bash
./scripts/run-end-to-end-tests.sh
```

[Failure semantics](docs/failure-semantics.md) documents every crash window and retry state.

## API examples

Query event-time telemetry with bounded keyset pagination:

```bash
curl --get http://localhost:8080/api/v1/telemetry \
  --data-urlencode 'facilityId=facility-alpha' \
  --data-urlencode 'assetId=zone-01' \
  --data-urlencode 'from=2026-08-29T00:00:00Z' \
  --data-urlencode 'to=2026-08-30T00:00:00Z' \
  --data-urlencode 'pageSize=10'
```

Create a replay with an explicit mapping and quality-rules version:

```bash
curl -X POST http://localhost:8080/api/v1/replays \
  -H 'Authorization: Bearer local-development-token-change-me' \
  -H 'Content-Type: application/json' \
  -d '{
    "facilityId":"facility-alpha",
    "from":"2026-08-29T20:00:00Z",
    "to":"2026-08-29T21:00:00Z",
    "mappingVersion":"controller-a-mapping-1.1.0",
    "qualityRulesVersion":"quality-rules-1.0.0",
    "reason":"Recover the newly mapped auxiliary temperature signal"
  }'
```

Errors use RFC 9457 problem details and stable `reasonCode` values. The generated runtime contract is checked against [the committed OpenAPI snapshot](contracts/openapi/telemetry-api-v1.json).

A successful page has stable event-time ordering and an opaque continuation cursor:

```json
{
  "items": [
    {
      "facilityId": "facility-alpha",
      "assetId": "zone-01",
      "signalId": "environment.air_temperature",
      "observedAt": "2026-08-29T20:00:00Z",
      "value": 24.2,
      "unit": "Cel",
      "quality": "GOOD",
      "flags": []
    }
  ],
  "nextCursor": "<opaque-keyset-cursor>"
}
```

## Contracts and storage

- Raw batches are deterministic gzip JSON, limited to 500 observations, protected by both `Content-Digest` and a stored SHA-256 checksum.
- Object keys partition by trusted gateway receipt time: `raw-observations/facility=.../date=.../hour=.../collector=.../batch=....json.gz`.
- Canonical identity is SHA-256 over facility, source system, source epoch, source sequence, and source tag.
- TimescaleDB stores event time (`observed_at`), receipt time, and processing time separately.
- Versioned YAML mapping files are immutable inputs to live and replay processing.

Schemas, examples, mapping rules, and reason codes are described in [Data contracts](docs/data-contracts.md).

## Verification

There is deliberately no CI/CD. The public verification contract is local and cross-platform:

```powershell
.\mvnw.cmd --no-transfer-progress verify
.\scripts\verify-local.ps1
```

```bash
./mvnw --no-transfer-progress verify
./scripts/verify-local.sh
```

`verify` runs unit and contract tests, Java 21 compilation, Spotless, SpotBugs, and JaCoCo reports. `verify-local` additionally parses both Compose profiles and every dashboard, builds and starts the real stack, compares live OpenAPI with its snapshot, and runs the failure suite.

Manual release audit (CycloneDX SBOM, OWASP Dependency-Check, resolved container IDs, and Docker Scout when installed):

```powershell
.\scripts\audit-local.ps1
```

```bash
./scripts/audit-local.sh
```

Set `NVD_API_KEY` in the process environment to enable OWASP Dependency-Check; the value is read by environment-variable name and is never stored in this repository or passed on the command line. Without it, the audit prints an explicit skip and continues with the remaining gates. Request a key from the [NVD API key service](https://nvd.nist.gov/developers/request-an-api-key). Docker Scout also requires an authenticated Docker session; when either external prerequisite is absent, the audit says exactly what was skipped and still writes the CycloneDX SBOM and resolved container inventory.

Last verified locally on 2026-08-30 with Java 21, Docker Engine 29.7.2, Docker Compose 5.4.0, and Git 2.53 on Windows 11 with Linux-script syntax checks through WSL2. See [Dependency baseline](docs/dependency-baseline.md).

## Design choices and limits

The central trade-off is explicit: the project spends extra local I/O and metadata writes to make acknowledgment, replay, and audit boundaries inspectable. It does not claim production readiness.

- One simulator, one collector, one gateway, one worker, and single-node data services keep the lab understandable.
- RabbitMQ quorum queues demonstrate durable semantics but have no replica on one node.
- SeaweedFS runs in single-node `mini` mode; TimescaleDB, Loki, Tempo, Prometheus, and Grafana are also single-node.
- The bearer token and default credentials are synthetic local controls, not an identity system.
- The 100,000-row collector spool holds about 3 hours 51 minutes at the default 7.2 observations/second; polling pauses at 80% and resumes at 60%.
- Simulator history is bounded to 250,000 observations (about 9.6 hours). An expired cursor creates a visible gap state and never silently skips.
- Replay accepts one active, maximum-24-hour run. No schema registry service, fleet rollout controller, cloud deployment, high availability, CI/CD, automatic retention, or continuous aggregate is included.

The rationale is recorded in [Architecture decision records](docs/adrs/001-at-least-once-delivery.md), and operational limitations are collected in [Public repository notes](docs/public-repository-notes.md).

## Repository map

```text
controller-simulator/   bounded cursor API and fault injection
edge-collector/         SQLite WAL spool, deterministic batching, retry state
telemetry-contracts/    DTOs, schemas, gzip/digest/identity utilities
telemetry-platform/     gateway, outbox, worker, query, inventory, replay
database/migrations/    Flyway schemas for SQLite and TimescaleDB
contracts/              JSON Schema, examples, registry, OpenAPI snapshot
config/                 immutable source mappings and quality rules
observability/          Prometheus, alerts, Alloy, Loki, Tempo, Grafana
scripts/                PowerShell/Bash verification and failure scenarios
docs/                   architecture, contracts, ADRs, runbooks, demo
```

## Documentation

- [Architecture](docs/architecture.md)
- [Data contracts](docs/data-contracts.md)
- [Failure semantics](docs/failure-semantics.md)
- [Local development](docs/local-development.md)
- [Demo guide](docs/demo.md)
- [Pipeline backlog runbook](docs/runbooks/pipeline-backlog.md)
- [Collector offline runbook](docs/runbooks/collector-offline.md)
- [Security policy](SECURITY.md)
- [Contributing](CONTRIBUTING.md)
- [Original project plan](PROJECT_PLAN.md)

Released under the [MIT License](LICENSE).
