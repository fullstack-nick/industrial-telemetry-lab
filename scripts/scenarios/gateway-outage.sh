#!/usr/bin/env bash

set -euo pipefail
[[ -z "${1:-}" ]] || export TELEMETRY_ENV_FILE="$1"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"
cd "$TELEMETRY_REPO_ROOT"

cleanup() { compose start telemetry-gateway >/dev/null 2>&1 || true; reset_simulator_faults >/dev/null 2>&1 || true; }
trap cleanup EXIT

scenario_header gateway-outage
start_core_stack
reset_simulator_faults
wait_for_spool_drain
before_status="$(collector_status)"
before_spool="$(printf '%s' "$before_status" | json_number spoolObservationCount)"
before_cursor="$(printf '%s' "$before_status" | json_number sourceCursor)"
compose stop telemetry-gateway
spool_grew() { [[ "$(collector_status | json_number spoolObservationCount)" -gt "$before_spool" ]]; }
wait_until 'collector buffers readings while the gateway is down' 35 spool_grew
during="$(collector_status)"
during_cursor="$(printf '%s' "$during" | json_number sourceCursor)"
assert_true 'source polling continues during the gateway outage' test "$during_cursor" -gt "$before_cursor"
batch_pending() { [[ "$(collector_status | json_number pendingBatchCount)" -gt 0 ]]; }
wait_until 'at least one exact compressed batch remains pending' 20 batch_pending
during="$(collector_status)"
printf 'Spool before outage: %s\n' "$before_spool"
printf 'Spool during outage: %s\n' "$(printf '%s' "$during" | json_number spoolObservationCount)"
compose start telemetry-gateway
wait_http http://localhost:8080/actuator/health/readiness
wait_for_spool_drain 150
scenario_pass gateway-outage
