# Contributing

Industrial Telemetry Lab welcomes focused fixes, tests, documentation improvements, and small reliability experiments. Keep contributions within the fictional environmental-qualification-lab setting and the deliberately local, single-source scope described in `PROJECT_PLAN.md`.

## Development setup

1. Install Java 21, Docker with Compose v2, and Git.
2. Copy `.env.example` to `.env`; use only synthetic local values.
3. Run `scripts/check-prerequisites.ps1` on PowerShell or `scripts/check-prerequisites.sh` on Bash.
4. Run `mvnw.cmd verify` or `./mvnw verify` before starting the stack.
5. Start the core and observability services with `docker compose --profile observability up -d --build`.

Do not commit `.env`, databases, logs, generated trace data, credentials, or Docker volume contents.

## Change discipline

- Preserve acknowledgment boundaries and failure semantics. A convenience change must not silently advance a cursor, recreate retry bytes, overwrite a raw object, or acknowledge a queue message before its database transaction commits.
- Treat a committed contract, mapping version, or quality-rules version as immutable. Add a new version instead of changing the meaning of an existing one.
- Add a migration for durable schema changes. Do not edit a migration already used by a published release.
- Keep metric labels bounded; identifiers and exception text belong in logs and traces.
- Use UTC event timestamps and keep event, receipt, and processing time distinct.
- Keep PowerShell and Bash entry points behaviorally equivalent.
- Do not add cloud deployment, infrastructure-as-code, or CI/CD workflows.

An architectural change should add or supersede an ADR in `docs/adrs/`. State the trade-off and the executable evidence that protects the decision.

## Verification

For Java-only changes:

```powershell
.\mvnw.cmd --no-transfer-progress verify
```

For runtime, contract, persistence, messaging, or Compose changes:

```powershell
.\scripts\verify-local.ps1
```

The Bash equivalents are `./mvnw --no-transfer-progress verify` and `./scripts/verify-local.sh`. Add a bounded assertion to the relevant scenario for any changed failure behavior. Avoid fixed long sleeps; use the polling helpers in `scripts/lib/`.

## Pull requests

Describe the behavior changed, the failure boundary affected, the commands run, and any deliberate limitation. Never include real facility, customer, machinery, employer, or operational data. By contributing, you agree that your contribution is licensed under the repository's MIT License.
