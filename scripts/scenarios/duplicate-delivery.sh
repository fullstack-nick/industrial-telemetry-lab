#!/usr/bin/env bash

set -euo pipefail
[[ -z "${1:-}" ]] || export TELEMETRY_ENV_FILE="$1"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"
cd "$TELEMETRY_REPO_ROOT"

cleanup() { reset_simulator_faults >/dev/null 2>&1 || true; }
trap cleanup EXIT

scenario_header duplicate-delivery
start_core_stack
reset_simulator_faults
duplicates_before="$(db_scalar 'SELECT COALESCE(SUM(duplicate_count),0) FROM ingestion_batch')"
out_of_order_sql="SELECT COUNT(*) FROM telemetry_sample WHERE flags @> '[\"OUT_OF_ORDER\"]'::jsonb"
out_of_order_before="$(db_scalar "$out_of_order_sql")"
set_simulator_faults '{"duplicateRate":1.0,"outOfOrderRate":1.0}'
duplicates_grew() { [[ "$(db_scalar 'SELECT COALESCE(SUM(duplicate_count),0) FROM ingestion_batch')" -gt "$duplicates_before" ]]; }
wait_until 'duplicate observations reach the idempotent worker' 50 duplicates_grew
reset_simulator_faults
out_of_order_grew() { [[ "$(db_scalar "$out_of_order_sql")" -gt "$out_of_order_before" ]]; }
wait_until 'late events are retained with OUT_OF_ORDER flags' 50 out_of_order_grew
duplicate_rows="$(db_scalar 'SELECT COUNT(*) FROM (SELECT observation_id FROM telemetry_sample_identity GROUP BY observation_id HAVING COUNT(*) > 1) d')"
assert_true 'deterministic identity constraint prevents duplicate canonical identities' test "$duplicate_rows" -eq 0
duplicates_after="$(db_scalar 'SELECT COALESCE(SUM(duplicate_count),0) FROM ingestion_batch')"
printf 'Duplicate outcomes added: %s\n' "$((duplicates_after - duplicates_before))"
scenario_pass duplicate-delivery
