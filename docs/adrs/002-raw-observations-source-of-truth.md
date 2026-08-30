# ADR 002: Raw observations are the source of truth

- Status: Accepted
- Date: 2026-08-29

## Context

Mapping, unit conversion, and quality decisions evolve. Keeping only canonical rows would make a past rejection impossible to explain or recover without returning to the original source.

## Decision

Retain the collector's exact immutable gzip batch in SeaweedFS before acknowledging ingestion. Treat TimescaleDB telemetry as a versioned projection. Store object key, checksum, contract version, min/max event time, and outcome counters in the manifest. Workers fetch and verify the raw object for both live work and replay.

## Consequences

Mapping 1.1 can recover an observation rejected under mapping 1.0, with complete provenance and without rewriting history. This costs extra local storage and creates an object/database reconciliation boundary. Automatic deletion and retention are deliberately excluded.

## Verification

`normal-operation` reconciles manifests and objects and balances raw observations against outcomes. `invalid-unit` and `unknown-tag-and-replay` prove rejected input remains durable and replayable.
