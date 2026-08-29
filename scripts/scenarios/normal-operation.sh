#!/usr/bin/env bash

set -euo pipefail
[[ -z "${1:-}" ]] || export TELEMETRY_ENV_FILE="$1"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"
cd "$TELEMETRY_REPO_ROOT"

cleanup() { reset_simulator_faults >/dev/null 2>&1 || true; }
trap cleanup EXIT

scenario_header normal-operation
start_core_stack
reset_simulator_faults
wait_for_spool_drain
before="$(db_scalar 'SELECT COUNT(*) FROM telemetry_sample')"
samples_grew() { [[ "$(db_scalar 'SELECT COUNT(*) FROM telemetry_sample')" -gt "$before" ]]; }
wait_until 'canonical telemetry continues to grow' 45 samples_grew
processing_complete() { [[ "$(db_scalar "SELECT COUNT(*) FROM ingestion_batch WHERE processing_status <> 'PROCESSED'")" -eq 0 ]]; }
wait_until 'all accepted manifests finish processing' 60 processing_complete

token="$(env_value LOCAL_API_TOKEN local-development-token-change-me)"
reconciliation="$(curl --fail --silent --show-error --header "Authorization: Bearer $token" http://localhost:8080/api/v1/admin/raw-objects/reconciliation)"
assert_true 'raw objects and manifests reconcile exactly' grep -q '"healthy":true' <<<"$reconciliation"

counts="$(db_scalar "SELECT COALESCE(SUM(observation_count),0) || '|' || COALESCE(SUM(accepted_count + flagged_count + rejected_count + duplicate_count),0) FROM ingestion_batch")"
expected="${counts%%|*}"
accounted="${counts##*|}"
assert_true 'every durable raw observation has a recorded processing outcome' test "$expected" -eq "$accounted"
status="$(collector_status)"
printf 'Raw observations persisted:   %s\n' "$expected"
printf 'Processing outcomes recorded: %s\n' "$accounted"
printf 'Collector spool observations: %s\n' "$(printf '%s' "$status" | json_number spoolObservationCount)"
scenario_pass normal-operation
