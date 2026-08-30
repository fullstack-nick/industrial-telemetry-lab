# ADR 008: Concurrent batch receipt

- Status: Accepted
- Date: 2026-08-29

## Context

Network retries can cause two requests for one batch to arrive concurrently. Object storage and PostgreSQL cannot participate in one distributed lock, and a check-before-write race could create contradictory manifests.

## Decision

Derive a PostgreSQL transaction-scoped advisory lock from `batchId`. After acquiring it, re-read the manifest, then use SeaweedFS create-if-absent. Identical existing digest means idempotent continuation; a different digest means deterministic `409 Conflict`. Never overwrite an object for an existing batch key. Commit one manifest and one outbox event.

## Consequences

Same-ID requests serialize only with each other, not globally. An object can still be orphaned by a crash before database commit, but the same-byte retry repairs it. Hash collision risk in advisory-lock key derivation can serialize unrelated batches temporarily without corrupting them.

## Verification

Gateway validation and the raw-object adapter enforce ID/digest consistency; the manifest primary key, unique object key, and outbox transaction provide the final constraints. Reconciliation exposes incomplete cross-store state.
