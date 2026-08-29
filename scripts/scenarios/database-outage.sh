#!/usr/bin/env bash

set -euo pipefail
[[ -z "${1:-}" ]] || export TELEMETRY_ENV_FILE="$1"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"
cd "$TELEMETRY_REPO_ROOT"

cleanup() { compose start timescaledb telemetry-gateway telemetry-worker >/dev/null 2>&1 || true; reset_simulator_faults >/dev/null 2>&1 || true; }
trap cleanup EXIT

scenario_header database-outage
start_core_stack
reset_simulator_faults
wait_for_spool_drain
before="$(collector_status)"
before_spool="$(printf '%s' "$before" | json_number spoolObservationCount)"
before_cursor="$(printf '%s' "$before" | json_number sourceCursor)"
compose stop timescaledb
spool_grew() { [[ "$(collector_status | json_number spoolObservationCount)" -gt "$before_spool" ]]; }
wait_until 'collector retains observations during database outage' 40 spool_grew
assert_true 'edge acquisition remains independent of the platform database' test "$(collector_status | json_number sourceCursor)" -gt "$before_cursor"
compose start timescaledb
user="$(env_value POSTGRES_USER telemetry)"
database="$(env_value POSTGRES_DB telemetry)"
database_ready() { compose exec -T timescaledb pg_isready -U "$user" -d "$database" >/dev/null; }
wait_until 'TimescaleDB accepts connections after restart' 90 database_ready
wait_http http://localhost:8080/actuator/health/readiness 120
wait_http http://localhost:8083/actuator/health/readiness 120
wait_for_spool_drain 180
scenario_pass database-outage
