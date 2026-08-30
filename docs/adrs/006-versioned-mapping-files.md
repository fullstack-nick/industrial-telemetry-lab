# ADR 006: Versioned mapping files

- Status: Accepted
- Date: 2026-08-29

## Context

Controller tags are source-specific and may change independently of stable domain signals. Mutating a mapping in place makes historical processing impossible to reproduce.

## Decision

Keep source mappings as immutable, version-named YAML files. Downstream APIs expose stable facility/asset/signal identifiers and canonical units, not controller tag conventions. Every processing attempt and replay records the selected mapping and quality-rules versions.

## Consequences

New source tags require a new mapping version and intentional deployment/replay. Multiple versions consume a small amount of configuration space but make decisions auditable. There is no remote schema registry in the initial project.

## Verification

Mapping tests cover parsing and conversion. `unknown-tag-and-replay` rejects the auxiliary tag under 1.0, accepts it under 1.1, and proves repeat replay is idempotent.
