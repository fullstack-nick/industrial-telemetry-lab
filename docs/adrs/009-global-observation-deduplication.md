# ADR 009: Global observation deduplication

- Status: Accepted
- Date: 2026-08-29

## Context

TimescaleDB requires a hypertable unique index to include its time partition key, so `(observation_id)` alone cannot enforce uniqueness across daily chunks.

## Decision

Use ordinary PostgreSQL table `telemetry_sample_identity` with `observation_id` as its primary key. In the same worker transaction, insert identity with `ON CONFLICT DO NOTHING`, then insert the hypertable row only when identity was new. Compare event time on conflict to distinguish duplicate from identity conflict. Rejected observations do not claim an identity.

## Consequences

Global deduplication works across chunks and rollbacks remove identity and projection together. The extra table/write is intentional. Rejections can later become canonical under a new mapping; rejection audit uniqueness is enforced separately.

## Verification

Duplicate and replay scenarios assert no duplicate identity or canonical rows. Schema constraints protect concurrent workers even if application prechecks race.
