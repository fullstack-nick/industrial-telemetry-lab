# Security policy

Industrial Telemetry Lab is an educational, fully local system. It is not production-ready and does not provide an internet-facing security boundary.

## Reporting a vulnerability

Please use GitHub's private vulnerability-reporting flow on the repository **Security** tab. Do not publish exploit details, credentials, or sensitive host information in a public issue. If private reporting is unavailable, open a public issue containing only a request for private contact and no vulnerability details.

Reports should include the affected commit, prerequisites, impact, a minimal reproduction, and any suggested mitigation. Maintainers will acknowledge a report when it is reviewed; this project does not promise a commercial response SLA.

## Supported version

Only the current `main` branch is supported. Historical commits and locally modified deployments are not maintained security releases.

## Local security boundary

- Every published Compose port binds to `127.0.0.1`.
- Modifying APIs require one synthetic local bearer token. Read-only telemetry, health, documentation, and local dashboards are intentionally accessible on the host.
- PostgreSQL, RabbitMQ, SeaweedFS, and Grafana credentials in `.env.example` are local demonstration defaults, not secrets suitable for a shared or hostile machine.
- Application logs exclude authorization headers, cookies, complete raw batches, and configured credentials.
- Containers run as non-root and with read-only root filesystems where the upstream image permits it. Named volumes remain writable because they hold durable state.
- The observability collector reads a dedicated log volume; the Docker socket is not mounted.
- There is no TLS, identity provider, authorization model, network segmentation, secret manager, encryption-at-rest key management, high availability, external alert delivery, or automatic patching.

If other users can access the host, replace every `.env` credential, keep Docker Desktop access restricted, and do not expose the ports through a proxy or firewall rule. See `docs/adrs/011-local-security-boundary.md` and `docs/local-development.md`.

## Manual release review

Run the repository-owned audit command before a public release:

```powershell
.\scripts\audit-local.ps1
```

```bash
./scripts/audit-local.sh
```

It emits a CycloneDX SBOM, runs OWASP Dependency-Check, records resolved container images, and uses Docker Scout when that optional plugin is available. Results are point-in-time evidence, not a guarantee that dependencies are vulnerability-free.
