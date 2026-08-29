#!/usr/bin/env bash

set -euo pipefail
[[ -z "${1:-}" ]] || export TELEMETRY_ENV_FILE="$1"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"
cd "$TELEMETRY_REPO_ROOT"

cleanup() { compose start telemetry-worker >/dev/null 2>&1 || true; reset_simulator_faults >/dev/null 2>&1 || true; }
trap cleanup EXIT

scenario_header worker-backlog
start_core_stack
reset_simulator_faults
manifests_before="$(db_scalar 'SELECT COUNT(*) FROM ingestion_batch')"
samples_before="$(db_scalar 'SELECT COUNT(*) FROM telemetry_sample')"
compose stop telemetry-worker
manifests_grew() { [[ "$(db_scalar 'SELECT COUNT(*) FROM ingestion_batch')" -gt "$manifests_before" ]]; }
queue_has_messages() { [[ "$(queue_ready_count)" -gt 0 ]]; }
wait_until 'gateway continues storing manifests while worker is stopped' 40 manifests_grew
wait_until 'RabbitMQ worker queue accumulates durable messages' 40 queue_has_messages
printf 'Ready messages at backlog peak: %s\n' "$(queue_ready_count)"
compose start telemetry-worker
wait_http http://localhost:8083/actuator/health/readiness
queue_is_empty() { [[ "$(queue_ready_count)" -eq 0 ]]; }
wait_until 'worker queue drains after recovery' 150 queue_is_empty
assert_true 'canonical writes resume after the worker restarts' test "$(db_scalar 'SELECT COUNT(*) FROM telemetry_sample')" -gt "$samples_before"
scenario_pass worker-backlog
