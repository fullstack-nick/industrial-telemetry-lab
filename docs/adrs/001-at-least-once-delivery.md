# ADR 001: At-least-once delivery

- Status: Accepted
- Date: 2026-08-29

## Context

HTTP responses can be lost, outbox publication can be repeated after a broker confirm, and RabbitMQ can redeliver after a worker commit but before acknowledgment. Removing every duplicate window would require cross-system transactions unavailable across SQLite, HTTP, object storage, PostgreSQL, and RabbitMQ.

## Decision

Use at-least-once movement and explicitly idempotent outcomes. Persist collector batch IDs and exact bytes before transmission; persist outbox events with manifests; use mandatory persistent messages, confirms, durable queues, and manual acknowledgments. Derive each observation ID from stable source coordinates and enforce it in PostgreSQL before inserting the TimescaleDB projection.

Exactly-once delivery is not claimed. A repeat is a valid processing outcome, not an exceptional transport failure.

## Consequences

Retries are safe and observable, but duplicate counters and audit records are part of normal operation. Every consumer must commit durable idempotency state before acknowledging. Identity conflicts are rejected rather than silently treated as duplicates.

## Verification

`duplicate-delivery` and `worker-backlog` prove repeat delivery adds no duplicate identity/canonical rows. The schema provides global identity uniqueness, and `failure-semantics.md` documents the confirm/commit windows.
