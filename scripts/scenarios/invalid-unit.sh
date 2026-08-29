#!/usr/bin/env bash

set -euo pipefail
[[ -z "${1:-}" ]] || export TELEMETRY_ENV_FILE="$1"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"
cd "$TELEMETRY_REPO_ROOT"

cleanup() { reset_simulator_faults >/dev/null 2>&1 || true; }
trap cleanup EXIT

scenario_header invalid-unit
start_core_stack
reset_simulator_faults
before="$(db_scalar "SELECT COUNT(*) FROM telemetry_rejection WHERE reason_code='UNSUPPORTED_RAW_UNIT'")"
raw_before="$(db_scalar 'SELECT COALESCE(SUM(observation_count),0) FROM ingestion_batch')"
set_simulator_faults '{"invalidUnitRate":1.0}'
rejections_grew() { [[ "$(db_scalar "SELECT COUNT(*) FROM telemetry_rejection WHERE reason_code='UNSUPPORTED_RAW_UNIT'")" -gt "$before" ]]; }
wait_until 'invalid units are rejected with a stable reason code' 50 rejections_grew
reset_simulator_faults
assert_true 'invalid source observations remain durable in raw batches' test "$(db_scalar 'SELECT COALESCE(SUM(observation_count),0) FROM ingestion_batch')" -gt "$raw_before"
scenario_pass invalid-unit
