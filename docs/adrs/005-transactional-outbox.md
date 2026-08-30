# ADR 005: Transactional outbox

- Status: Accepted
- Date: 2026-08-29

## Context

Publishing to RabbitMQ before a manifest commit can expose a reference that does not exist. Committing the manifest before a direct publish can lose processing work if the process crashes between them.

## Decision

Insert `ingestion_batch` and `outbox_event` in one PostgreSQL transaction. A dispatcher claims due unpublished rows with `FOR UPDATE SKIP LOCKED`, publishes a persistent mandatory reference with W3C trace context, waits for a positive confirm, and only then sets `published_at`.

## Consequences

Committed ingestion always has recoverable publish intent. A crash after broker confirmation but before `published_at` can republish; ADR 001's idempotency handles it. PostgreSQL is therefore part of the gateway acknowledgment boundary.

## Verification

Outbox backlog/age metrics and alerts expose dispatch failure. `worker-backlog` proves gateway ingestion and durable queueing remain decoupled from the worker.
