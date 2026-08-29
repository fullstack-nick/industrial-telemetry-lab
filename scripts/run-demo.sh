#!/usr/bin/env bash

set -euo pipefail
[[ -z "${1:-}" ]] || export TELEMETRY_ENV_FILE="$1"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/lib/common.sh"
cd "$TELEMETRY_REPO_ROOT"
trap 'reset_simulator_faults >/dev/null 2>&1 || true' EXIT

printf 'Starting the complete local portfolio stack...\n'
compose --profile observability up -d --build
wait_core_stack
wait_http http://localhost:3000/api/health 120
reset_simulator_faults
set_simulator_faults '{"duplicateRate":0.08,"outOfOrderRate":0.08,"invalidUnitRate":0.04,"newUnknownTagEnabled":true}'
sleep 12
reset_simulator_faults
cat <<'TEXT'
Demo evidence is flowing. Open:
  Grafana dashboards  http://localhost:3000/dashboards
  OpenAPI / Swagger   http://localhost:8080/swagger-ui.html
  RabbitMQ management http://localhost:15672
  SeaweedFS filer     http://localhost:8888

Run docs/demo.md for the complete outage, backlog, replay, raw-object, and trace walkthrough.
TEXT
