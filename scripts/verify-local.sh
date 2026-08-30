#!/usr/bin/env bash

set -euo pipefail
[[ -z "${1:-}" ]] || export TELEMETRY_ENV_FILE="$1"
skip_scenarios="${SKIP_SCENARIOS:-false}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/lib/common.sh"
cd "$TELEMETRY_REPO_ROOT"

"$script_dir/check-prerequisites.sh"
printf 'Running the Maven unit, formatting, SpotBugs, and coverage gates...\n'
./mvnw --no-transfer-progress verify
compose config --quiet
compose --profile observability config --quiet
compose_config="$(compose --profile observability config)"
published_count="$(grep -c 'published:' <<<"$compose_config" || true)"
loopback_count="$(grep -c 'host_ip: 127.0.0.1' <<<"$compose_config" || true)"
assert_true 'every published port binds only to loopback' test "$published_count" -eq "$loopback_count"
assert_true 'no service mounts the Docker daemon socket' bash -c '! grep -q "/var/run/docker.sock" <<<"$1"' _ "$compose_config"
for dashboard in observability/grafana/dashboards/*.json; do
  grep -q '"title"' "$dashboard"
done
printf 'ASSERT PASS: Compose and dashboard artifacts parse cleanly\n'
compose up -d --build
wait_core_stack
application_logs="$(compose logs --no-color controller-simulator edge-collector telemetry-gateway telemetry-worker)"
for name in LOCAL_API_TOKEN LOCAL_ADMIN_TOKEN POSTGRES_PASSWORD RABBITMQ_DEFAULT_PASS OBJECT_STORE_ACCESS_KEY OBJECT_STORE_SECRET_KEY GRAFANA_ADMIN_PASSWORD; do
  secret="$(env_value "$name")"
  if [[ -n "$secret" ]]; then
    assert_true "$name value is absent from application logs" bash -c '[[ "$1" != *"$2"* ]]' _ "$application_logs" "$secret"
  fi
done
"$script_dir/verify-openapi.sh"
if [[ "$skip_scenarios" != true ]]; then
  "$script_dir/run-end-to-end-tests.sh" "$TELEMETRY_ENV_FILE"
fi
printf 'Local verification passed.\n'
