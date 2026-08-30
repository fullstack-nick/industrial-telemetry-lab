# ADR 007: Future collector rollouts

- Status: Design only; not implemented
- Date: 2026-08-29

## Context

The inventory records collector, configuration, and source-adapter versions, but the initial lab has one collector and no fleet-control plane.

## Future decision shape

A larger deployment could sign immutable collector/configuration artifacts, stage them through canary, small, and broad rollout rings, enforce compatibility against contract versions, and require health/freshness gates before promotion. Collectors would pull desired state, verify signatures, install atomically, retain a rollback slot, and report observed version plus rollout status in heartbeats.

Rollout orchestration must never reset the SQLite cursor/spool or delete pending batch bytes. A rollback would restore software/configuration while retaining the same durable data volume. Offline collectors would not be force-skipped; their desired/observed divergence would remain visible.

## Consequences

No OTA endpoint, artifact server, signing key, rollout database, or ring controller exists in this repository. Adding one requires a threat model, authorization model, key rotation, recovery testing, and a new accepted ADR.
