#!/usr/bin/env bash

set -euo pipefail

TELEMETRY_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TELEMETRY_ENV_FILE="${TELEMETRY_ENV_FILE:-}"

use_telemetry_environment() {
  local candidate="${1:-}"
  if [[ -z "$candidate" ]]; then
    candidate="$TELEMETRY_REPO_ROOT/.env"
    if [[ ! -f "$candidate" ]]; then
      candidate="$TELEMETRY_REPO_ROOT/.env.example"
      printf 'INFO: .env is absent; using the synthetic .env.example values.\n'
    fi
  fi
  TELEMETRY_ENV_FILE="$(cd "$(dirname "$candidate")" && pwd)/$(basename "$candidate")"
  export TELEMETRY_ENV_FILE
}

env_value() {
  local name="$1"
  local fallback="${2:-}"
  local value
  value="$(sed -n "s/^${name}=//p" "$TELEMETRY_ENV_FILE" | tail -n 1)"
  printf '%s' "${value:-$fallback}"
}

compose() {
  docker compose --env-file "$TELEMETRY_ENV_FILE" "$@"
}

db_scalar() {
  local sql="$1"
  local user database
  user="$(env_value POSTGRES_USER telemetry)"
  database="$(env_value POSTGRES_DB telemetry)"
  compose exec -T timescaledb psql -U "$user" -d "$database" -At -v ON_ERROR_STOP=1 -c "$sql" | tr -d '\r' | tail -n 1
}

queue_ready_count() {
  local queue="${1:-telemetry.main}"
  local value
  value="$(compose exec -T rabbitmq rabbitmqctl list_queues --silent name messages_ready | tr -d '\r' | awk -v q="$queue" '$1 == q { print $2 }')"
  [[ -n "$value" ]] || { printf 'Queue %s was not declared.\n' "$queue" >&2; return 1; }
  printf '%s' "$value"
}

wait_until() {
  local description="$1"
  local timeout="$2"
  shift 2
  local deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    if "$@" >/dev/null 2>&1; then
      printf 'ASSERT PASS: %s\n' "$description"
      return 0
    fi
    sleep 2
  done
  printf 'ASSERT FAIL: timed out after %ss: %s\n' "$timeout" "$description" >&2
  return 1
}

assert_true() {
  local description="$1"
  shift
  if "$@"; then
    printf 'ASSERT PASS: %s\n' "$description"
  else
    printf 'ASSERT FAIL: %s\n' "$description" >&2
    return 1
  fi
}

wait_http() {
  local url="$1"
  local timeout="${2:-90}"
  wait_until "$url is reachable" "$timeout" curl --fail --silent --show-error --max-time 3 "$url"
}

wait_core_stack() {
  wait_http http://localhost:8081/actuator/health/readiness
  wait_http http://localhost:8080/actuator/health/readiness
  wait_http http://localhost:8083/actuator/health/readiness
  wait_http http://localhost:8082/actuator/health/readiness
}

start_core_stack() {
  compose up -d
  wait_core_stack
}

collector_status() {
  curl --fail --silent --show-error http://localhost:8082/collector/v1/status
}

json_number() {
  local field="$1"
  sed -nE "s/.*\"${field}\":([0-9]+).*/\1/p"
}

set_simulator_faults() {
  local body="$1"
  local token
  token="$(env_value LOCAL_API_TOKEN local-development-token-change-me)"
  curl --fail --silent --show-error --request PUT \
    --header "Authorization: Bearer $token" \
    --header 'Content-Type: application/json' \
    --data "$body" \
    http://localhost:8081/controller/v1/faults >/dev/null
}

reset_simulator_faults() {
  set_simulator_faults '{"duplicateRate":0,"outOfOrderRate":0,"invalidUnitRate":0,"badQualityRate":0,"futureTimestampRate":0,"responseDelayMs":0,"connectionAvailable":true,"newUnknownTagEnabled":false}'
}

spool_is_empty() {
  local status spool pending
  status="$(collector_status)"
  spool="$(printf '%s' "$status" | json_number spoolObservationCount)"
  pending="$(printf '%s' "$status" | json_number pendingBatchCount)"
  [[ "$spool" -eq 0 && "$pending" -eq 0 ]]
}

wait_for_spool_drain() {
  wait_until 'collector spool and pending batches drain' "${1:-120}" spool_is_empty
}

scenario_header() {
  printf '\nSCENARIO: %s\n' "$1"
  printf '%*s\n' "$((10 + ${#1}))" '' | tr ' ' '='
}

scenario_pass() {
  printf 'Result: PASS (%s)\n' "$1"
}

use_telemetry_environment "$TELEMETRY_ENV_FILE"
