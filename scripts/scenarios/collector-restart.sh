#!/usr/bin/env bash

set -euo pipefail
[[ -z "${1:-}" ]] || export TELEMETRY_ENV_FILE="$1"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"
cd "$TELEMETRY_REPO_ROOT"

cleanup() { compose start telemetry-gateway edge-collector >/dev/null 2>&1 || true; reset_simulator_faults >/dev/null 2>&1 || true; }
trap cleanup EXIT

scenario_header collector-restart
start_core_stack
reset_simulator_faults
wait_for_spool_drain
compose stop telemetry-gateway
backlog_exists() { [[ "$(collector_status | json_number spoolObservationCount)" -gt 0 ]]; }
wait_until 'a durable collector backlog exists before restart' 35 backlog_exists
before="$(collector_status)"
before_epoch="$(sed -nE 's/.*"sourceEpoch":"([^"]+)".*/\1/p' <<<"$before")"
before_cursor="$(printf '%s' "$before" | json_number sourceCursor)"
before_spool="$(printf '%s' "$before" | json_number spoolObservationCount)"
compose restart edge-collector
wait_http http://localhost:8082/actuator/health/readiness
after="$(collector_status)"
after_epoch="$(sed -nE 's/.*"sourceEpoch":"([^"]+)".*/\1/p' <<<"$after")"
assert_true 'source epoch survives collector restart' test "$after_epoch" = "$before_epoch"
assert_true 'durable source cursor never rewinds' test "$(printf '%s' "$after" | json_number sourceCursor)" -ge "$before_cursor"
assert_true 'buffered observations survive collector restart' test "$(printf '%s' "$after" | json_number spoolObservationCount)" -ge "$before_spool"
compose start telemetry-gateway
wait_http http://localhost:8080/actuator/health/readiness
wait_for_spool_drain 150
scenario_pass collector-restart
