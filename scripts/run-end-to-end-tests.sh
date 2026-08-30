#!/usr/bin/env bash

set -euo pipefail
[[ -z "${1:-}" ]] || export TELEMETRY_ENV_FILE="$1"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/lib/common.sh"
cd "$TELEMETRY_REPO_ROOT"

cleanup() {
  reset_simulator_faults >/dev/null 2>&1 || true
  compose start timescaledb rabbitmq seaweedfs controller-simulator telemetry-gateway telemetry-worker edge-collector >/dev/null 2>&1 || true
}
trap cleanup EXIT

printf 'Building and starting the core telemetry stack...\n'
compose up -d --build
wait_core_stack
for scenario in normal-operation ingestion-boundaries gateway-outage collector-restart worker-backlog database-outage duplicate-delivery invalid-unit unknown-tag-and-replay; do
  "$script_dir/scenarios/$scenario.sh" "$TELEMETRY_ENV_FILE"
done
printf 'All nine end-to-end scenarios passed.\n'
