# ADR 011: Local security boundary

- Status: Accepted
- Date: 2026-08-29

## Context

The project is a laptop demonstration, not an internet service, but its admin consoles and modifying endpoints still need a clear boundary that reviewers can reason about.

## Decision

Bind all published ports to loopback. Require one constant-time-compared synthetic bearer token for ingestion, replay, fault injection, heartbeat, gap recovery, overload, and reconciliation; allow local read-only telemetry, documentation, health, and dashboards. Keep credentials in untracked `.env`, with explicitly local-only examples. Never log authorization/cookies, full raw batches, or configured secrets. Run non-root/read-only containers where supported and collect logs through a read-only volume rather than the Docker socket.

## Consequences

This limits accidental LAN exposure and demonstrates secret hygiene, but it is not authentication suitable for untrusted local users. There is no TLS, per-user authorization, audit identity, secret manager, or encryption-at-rest key management. Shared machines require replaced credentials and restricted Docker access.

## Verification

Compose validation asserts loopback host bindings and no Docker socket mount. Local log checks scan for configured secret values. `SECURITY.md` documents reporting and the manual SBOM/vulnerability audit.
