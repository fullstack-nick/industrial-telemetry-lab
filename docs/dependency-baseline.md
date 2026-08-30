# Dependency baseline

This point-in-time baseline was verified on 2026-08-30. Versions are pinned in `pom.xml`, `docker-compose.yml`, or the Maven Wrapper. A version entry is evidence of what was tested, not a promise of continued vulnerability-free status.

## Application and build

| Component | Version | License | Primary source |
| --- | --- | --- | --- |
| Java | 21 | GPLv2 + Classpath Exception (OpenJDK) | <https://openjdk.org/projects/jdk/21/> |
| Maven Wrapper distribution | 3.9.16 | Apache-2.0 | <https://maven.apache.org/> |
| Spring Boot | 4.1.1 | Apache-2.0 | <https://spring.io/projects/spring-boot> |
| AWS SDK for Java v2 | 2.54.7 | Apache-2.0 | <https://github.com/aws/aws-sdk-java-v2> |
| Flyway | 13.4.0 | Apache-2.0 core | <https://github.com/flyway/flyway> |
| SQLite JDBC | 3.53.4.0 | Apache-2.0 | <https://github.com/xerial/sqlite-jdbc> |
| springdoc-openapi | 3.1.0 | Apache-2.0 | <https://springdoc.org/> |
| OpenTelemetry annotations / Java agent | 2.28.1 | Apache-2.0 | <https://github.com/open-telemetry/opentelemetry-java-instrumentation> |
| JSON Schema Validator | 2.0.7 | Apache-2.0 | <https://github.com/networknt/json-schema-validator> |
| Awaitility | 4.3.0 | Apache-2.0 | <https://www.awaitility.org/> |
| Testcontainers BOM | 2.0.5 | MIT | <https://java.testcontainers.org/> |
| Spotless Maven plugin | 3.10.1 | Apache-2.0 | <https://github.com/diffplug/spotless> |
| SpotBugs Maven plugin | 4.10.4.0 | LGPL-2.1 | <https://spotbugs.github.io/> |
| JaCoCo | 0.8.15 | EPL-2.0 | <https://www.jacoco.org/jacoco/> |
| CycloneDX Maven plugin | 2.9.3 | Apache-2.0 | <https://github.com/CycloneDX/cyclonedx-maven-plugin> |
| OWASP Dependency-Check | 13.0.0 | Apache-2.0 | <https://owasp.org/www-project-dependency-check/> |

Transitive licenses and package hashes are recorded by the generated CycloneDX aggregate SBOM rather than duplicated here.

## Container images

| Role | Pinned tag | Immutable registry digest | Upstream license | Primary source |
| --- | --- | --- | --- | --- |
| Application build | `maven:3.9.16-eclipse-temurin-21` | `sha256:8f6ac126f7810bb5549c4cd122d2bf0e9cda5bdeb0838aa928f09e779fd8bef8` | Apache-2.0 / GPLv2 + Classpath Exception | <https://hub.docker.com/_/maven> |
| Java agent source stage | `otel/autoinstrumentation-java:2.28.1` | `sha256:41b92978e61d13d4f32c6eb20c6ae7821a73ffdec8539bc6a73858e884b411d8` | Apache-2.0 | <https://github.com/open-telemetry/opentelemetry-java-instrumentation> |
| Application runtime | `eclipse-temurin:21.0.8_9-jre-jammy` | `sha256:db1689535962d757a5adabf57387584ed543d38c0b9d1fe870123ea362ad73b0` | GPLv2 + Classpath Exception | <https://hub.docker.com/_/eclipse-temurin> |
| TimescaleDB | `timescale/timescaledb:2.29.2-pg17` | `sha256:bc8527e62f70f0766b29515077965025872fabb5349db421565f69ee273baf2d` | Timescale License / Apache-2.0 components | <https://github.com/timescale/timescaledb> |
| RabbitMQ | `rabbitmq:4.3.5-management` | `sha256:45226f38499559b9f56875c752cc6689ff90e8f20796fe80fd9bc28d64723031` | MPL-2.0 | <https://github.com/rabbitmq/rabbitmq-server> |
| SeaweedFS | `chrislusf/seaweedfs:4.44` | `sha256:e67e8c385484120b78bff47ba5f4debbca47fbd27ed1a39f016f47e8baea615b` | Apache-2.0 | <https://github.com/seaweedfs/seaweedfs> |
| Prometheus | `prom/prometheus:v3.14.0` | `sha256:5ce7540c3c00ef4ab0c9d2c995c6a5b9c421f44b4a115d97a2c7af3b1c21cbb0` | Apache-2.0 | <https://github.com/prometheus/prometheus> |
| OpenTelemetry Collector | `otel/opentelemetry-collector-contrib:0.159.0` | `sha256:1f2c54a30e713fac6b3ae77a1ec84010c2007e29ced8ec666214fc2f6739c1cc` | Apache-2.0 | <https://github.com/open-telemetry/opentelemetry-collector-contrib> |
| Tempo | `grafana/tempo:3.0.3` | `sha256:0296560ac66f8a3600d7fb3014a52c189d4d9c3549ad6ff441bf2409855d68d5` | AGPL-3.0 | <https://github.com/grafana/tempo> |
| Loki | `grafana/loki:3.7.7` | `sha256:d70e4659623f3e109af669cae76fe2a5dd5be54e2298fe8aed380d982fbc2500` | AGPL-3.0 | <https://github.com/grafana/loki> |
| Alloy | `grafana/alloy:v1.19.2` | `sha256:b8ec653c44235fbe910879145dac3597d66b0aaecf60bcbbe82580767771a839` | Apache-2.0 | <https://github.com/grafana/alloy> |
| Grafana | `grafana/grafana:13.2.0` | `sha256:3fd54ae1214669f8355f065ec9f6445d5279a3d77095ab048ca045685272429b` | AGPL-3.0 | <https://github.com/grafana/grafana> |

Each Dockerfile and Compose reference retains its readable version tag and appends the recorded immutable digest. The listed digests are the registry manifests or multi-platform indexes resolved on the verification date, so a tag retarget cannot silently change the build. `scripts/audit-local.*` writes a fresh repository-digest/image-ID report—including Dockerfile build stages—to `target/security/container-images.json`; preserve that artifact with release evidence.

## Verification environment

The last full local run used Windows 11, OpenJDK 21, Maven Wrapper 3.9.16, Docker Engine 29.7.2, Docker Compose 5.4.0, Git 2.53, and WSL2 for Bash syntax validation. Both Compose profiles, dashboard JSON, the OpenAPI snapshot, six Prometheus scrape endpoints, logs, traces, and the nine recovery/boundary scenarios were validated.

## Update procedure

1. Review upstream release notes, compatibility, license, and container architecture.
2. Change one dependency family or image at a time.
3. Generate the SBOM and vulnerability report with `scripts/audit-local.*`.
4. Run `mvnw verify` and `scripts/verify-local.*`.
5. Inspect dashboard, log, trace, raw-object, and failure-scenario evidence.
6. Update this baseline and the README verification date only after the full run passes.

OWASP Dependency-Check reads its NVD credential from the `NVD_API_KEY` process environment variable. The audit scripts never persist or echo the value. If the variable is absent, they mark that data source as skipped and continue generating the SBOM and resolved-image inventory. Docker Scout SARIF generation likewise requires an authenticated Docker session; an unauthenticated run is reported as an explicit skip rather than a false pass.
