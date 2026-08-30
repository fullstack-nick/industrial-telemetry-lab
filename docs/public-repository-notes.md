# Public repository notes

## Purpose

This repository is an independent educational reliability lab. Its environmental qualification facility, empty equipment enclosures, systems, identifiers, readings, incidents, and screenshots are fictional and synthetic. No implementation or documentation represents an employer, customer, job posting, real facility, or internal system.

## Deliberate boundaries

- Fully local Docker Compose; no cloud account, deployment, infrastructure-as-code, or Terraform.
- No CI/CD or hosted workflow. Verification is explicit, reproducible, and local.
- One control source, one collector, twelve zones, three baseline signals, one raw-first platform, one replay path.
- Single-node TimescaleDB, RabbitMQ, SeaweedFS, Prometheus, Loki, Tempo, and Grafana.
- Synthetic bearer token and static local credentials rather than a production identity system.
- Loopback-only published ports; no TLS or internet-facing exposure.
- Bounded simulator history, SQLite spool, request sizes, query ranges, queue bytes, replay interval, and log retention.
- No high availability, disaster recovery, backup scheduler, fleet rollout service, automatic retention, continuous aggregates, schema-registry service, external paging, or production SLO claim.

These constraints make the acknowledgment and recovery boundaries inspectable on a developer laptop. They are not shortcuts that should be carried into a production architecture without a new threat model, capacity model, redundancy design, and operational ownership.

## Claims supported by executable evidence

The repository demonstrates durable-before-cursor edge acquisition, exact retry bytes, raw-first acknowledgment, transactional outbox publication, at-least-once delivery, global identity deduplication, precise rejection audit, version-pinned replay, event-time query, correlated metrics/logs/traces, and bounded recovery scenarios. `scripts/verify-local.*` and `scripts/run-end-to-end-tests.*` are the public evidence contract.

It does not claim exactly-once transport, lossless operation beyond bounded storage, one-node availability, automatic remediation, vulnerability-free dependencies, or production readiness.

## Public-content review checklist

Before a public-polish release:

1. Search tracked text and assets for employer, product, job-posting, customer, location, and application references using an external private checklist; do not commit the excluded-name list.
2. Confirm every identifier and sample is generic and synthetic.
3. Confirm no `.env`, key, database, log, trace, object payload, or host-specific path is tracked.
4. Confirm `.github/workflows` and `*.tf` are absent.
5. Confirm screenshots contain no desktop notifications, usernames, unrelated tabs, tokens, credentials, or host paths.
6. Confirm README claims map to an executable assertion or clearly labeled design limitation.
7. Confirm ports remain bound to loopback and default credentials remain labeled local-only.
8. Run `verify-local` from a fresh clone and record the date/tool versions in the README.

## Repository history

Commits are organized around planning, owner decisions, the durable pipeline, observability/resilience, and public delivery. The plan is preserved in `PROJECT_PLAN.md`; implementation refinements are recorded as ADRs rather than silently rewriting that source plan.
