# Data contracts

## Contract ownership

Raw and canonical schemas live in `contracts/`; Java DTOs and shared validation utilities live in `telemetry-contracts`. Committed schema, mapping, registry, and quality-rule versions are immutable. A semantic change gets a new identifier and file so both live processing and replay can name exactly what they used.

| Contract | Current identifier | Repository source |
| --- | --- | --- |
| Raw batch | `raw-observation.batch.v1` | `contracts/raw-observation.batch.v1.schema.json` |
| Canonical sample | `telemetry.sample.v1` | `contracts/telemetry.sample.v1.schema.json` |
| Source mapping | `controller-a-mapping-1.0.0`, `controller-a-mapping-1.1.0` | `config/mappings/` |
| Quality rules | `quality-rules-1.0.0` | `config/quality-rules/` |
| Signal registry | versioned YAML document | `contracts/signal-registry.yaml` |
| HTTP API | OpenAPI snapshot | `contracts/openapi/telemetry-api-v1.json` |

## Raw observation batch

The collector sends `Content-Type: application/json`, `Content-Encoding: gzip`, and `Content-Digest: sha-256=:<base64>:`. The digest covers the exact compressed HTTP bytes, while the stored checksum is the same SHA-256 in lowercase hexadecimal form. Retries reuse the persisted compressed BLOB byte for byte.

Representative envelope:

```json
{
  "contractVersion": "raw-observation.batch.v1",
  "batchId": "0b30bfc6-f3c5-52bf-b2aa-18870195fd7f",
  "facilityId": "facility-alpha",
  "collectorId": "edge-gateway-01",
  "collectorVersion": "1.0.0",
  "createdAt": "2026-08-29T20:00:05Z",
  "observations": [
    {
      "sourceSystem": "controller-a",
      "sourceEpoch": "2eeb68b7-ff27-4f5a-91dc-e721f59e518e",
      "sourceSequence": 1,
      "sourceTag": "ZONE_01.TEMP_PV",
      "observedAt": "2026-08-29T20:00:00Z",
      "rawValue": 21.4,
      "rawUnit": "degC",
      "sourceQualityCode": 192
    }
  ]
}
```

The exact maintained examples are `contracts/examples/valid-raw-batch.json`, `invalid-unit-batch.json`, and `unknown-tag-batch.json`.

Initial input limits are 500 observations, 1 MiB compressed, 10 MiB decompressed, 64-character facility/collector identifiers, and a 256-character source tag. The gateway and worker both enforce the observation and field limits. Unsupported versions, checksum mismatch, wrong types, absent required values, invalid timestamps, or excess size fail with RFC 9457 problem details and a stable `reasonCode`.

## Deterministic identities

`observationId` is SHA-256 of these length-delimited UTF-8 fields in order:

```text
facilityId | sourceSystem | sourceEpoch | sourceSequence | sourceTag
```

Length delimiting, rather than visual delimiter concatenation, prevents ambiguous identities. A source process restart must issue a new epoch before its sequence resets. The same raw observation in another batch or replay has the same ID; a genuinely new source epoch has a new ID.

`batchId` is deterministic for the ordered observations selected into the persisted outbound batch. Its exact payload and digest are stored with that ID; using one ID with different bytes is a conflict.

## Mapping and quality

Mapping removes source-specific tags from downstream query semantics. Version 1.0 maps each zone's temperature, relative humidity, and pressure tags to stable asset/signal identifiers and canonical units. Version 1.1 adds the deliberately unknown auxiliary temperature tag used by the replay scenario.

Processing has four outcomes:

| Outcome | Meaning | Canonical row |
| --- | --- | --- |
| `ACCEPTED` | Mapped, valid, good data | Inserted once |
| `FLAGGED` | Mapped and retained with a bounded quality flag | Inserted once |
| `REJECTED` | Cannot safely become canonical telemetry | Not inserted; rejection audit recorded |
| `DUPLICATE` | Identity already exists with the same event time | Not inserted again |

Stable reason codes are: `MISSING_SOURCE_TAG`, `UNKNOWN_SOURCE_TAG`, `UNSUPPORTED_RAW_UNIT`, `NON_NUMERIC_VALUE`, `NON_FINITE_VALUE`, `VALUE_OUT_OF_RANGE`, `TIMESTAMP_TOO_FAR_IN_FUTURE`, `OUT_OF_ORDER`, `LATE_ARRIVAL`, `BAD_SOURCE_QUALITY`, `DUPLICATE_OBSERVATION`, `IDENTITY_CONFLICT`, `CHECKSUM_MISMATCH`, `UNSUPPORTED_CONTRACT_VERSION`, `INVALID_ENVELOPE`, `TOO_MANY_OBSERVATIONS`, and `FIELD_TOO_LONG`.

Out-of-order, late-arrival, and bad-source-quality states may be retained as flags when the value remains usable. Unsupported units, non-numeric/non-finite values, out-of-range values, unknown tags, and unsafe future timestamps are rejected.

## Canonical sample

Canonical telemetry records normalized value/unit plus full provenance:

- stable facility, asset, and signal IDs;
- event, receipt, and processing timestamps;
- normalized numeric value and unit;
- quality and bounded flag list;
- source system, epoch, sequence, and original tag;
- collector and collector version;
- mapping and quality-rules versions;
- raw batch ID.

Raw data remains the source of truth; canonical telemetry is a replayable projection. A rejection never deletes or modifies the raw gzip object.

## REST behavior

Read endpoints:

- `GET /api/v1/signals`
- `GET /api/v1/telemetry`
- `GET /api/v1/batches/{batchId}`
- `GET /api/v1/collectors` and `GET /api/v1/collectors/{collectorId}`
- `GET /api/v1/replays/{replayId}`

Modifying or operational endpoints require `Authorization: Bearer <LOCAL_API_TOKEN>`:

- `POST /api/v1/ingestion/batches`
- `POST /api/v1/collectors/{collectorId}/heartbeat`
- `POST /api/v1/replays`
- `PUT /api/v1/admin/overload`
- `GET /api/v1/admin/raw-objects/reconciliation`
- simulator fault/restart endpoints and collector gap recovery.

Telemetry queries accept a maximum 24-hour UTC range, default to 200 rows, cap at 1,000, and use opaque keyset cursors ordered by `observedAt` then `observationId`. The runtime `/v3/api-docs` document is compared byte-for-byte after canonical formatting with the committed OpenAPI snapshot by `scripts/verify-openapi.*`.

## Raw object layout

The object key uses trusted gateway receipt time:

```text
raw-observations/
  facility=<facilityId>/date=<yyyy-MM-dd>/hour=<HH>/
  collector=<collectorId>/batch=<batchId>.json.gz
```

Object metadata carries the SHA-256 checksum. The key and digest are also stored in `ingestion_batch`, allowing reconciliation to report an object without a manifest or a manifest without an object.
