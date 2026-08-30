#!/usr/bin/env bash

set -euo pipefail
[[ -z "${1:-}" ]] || export TELEMETRY_ENV_FILE="$1"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../lib/common.sh"

scenario_header 'ingestion boundaries'
temp_dir="$(mktemp -d)"
trap 'rm -rf -- "$temp_dir"' EXIT
token="$(env_value LOCAL_API_TOKEN local-development-token-change-me)"

new_uuid() {
  if command -v uuidgen >/dev/null 2>&1; then uuidgen | tr '[:upper:]' '[:lower:]'; else tr -d '\r\n' </proc/sys/kernel/random/uuid; fi
}

make_batch() {
  local batch_id="$1" epoch="$2" value="$3" unit="$4" destination="$5"
  local now
  now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '{"contractVersion":"raw-observation.batch.v1","batchId":"%s","collectorId":"boundary-probe-01","collectorVersion":"1.0.0","facilityId":"facility-alpha","createdAt":"%s","observations":[{"sourceSystem":"boundary-probe","sourceEpoch":"%s","sourceSequence":1,"sourceTag":"CTRL_A.ZONE[07].TEMP_PV","observedAt":"%s","rawValue":%s,"rawUnit":"%s","sourceQualityCode":192}]}' \
    "$batch_id" "$now" "$epoch" "$now" "$value" "$unit" | gzip -n -c >"$destination"
}

digest_header() {
  printf 'sha-256=:%s:' "$(openssl dgst -sha256 -binary "$1" | openssl base64 -A)"
}

upload() {
  local file="$1" extra_header="${2:-}"
  local digest
  digest="$(digest_header "$file")"
  args=(--silent --show-error --output "$temp_dir/response-$RANDOM.json" --write-out '%{http_code}' --request POST \
    --header "Authorization: Bearer $token" --header 'Content-Type: application/json' \
    --header 'Content-Encoding: gzip' --header "Content-Digest: $digest" --data-binary "@$file")
  [[ -z "$extra_header" ]] || args+=(--header "$extra_header")
  curl "${args[@]}" http://localhost:8080/api/v1/ingestion/batches
}

outbox_empty() { [[ "$(db_scalar 'SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL')" -eq 0 ]]; }
wait_until 'outbox is initially drained' 60 outbox_empty

same_id="$(new_uuid)"
make_batch "$same_id" "$(new_uuid)" 21.5 degC "$temp_dir/same.gz"
upload "$temp_dir/same.gz" >"$temp_dir/code-1" & first_pid=$!
upload "$temp_dir/same.gz" >"$temp_dir/code-2" & second_pid=$!
wait "$first_pid"; wait "$second_pid"
assert_true 'concurrent identical uploads both receive idempotent acceptance' bash -c '[[ "$(<"$1")" == 202 && "$(<"$2")" == 202 ]]' _ "$temp_dir/code-1" "$temp_dir/code-2"
assert_true 'concurrent identical uploads create one manifest' test "$(db_scalar "SELECT COUNT(*) FROM ingestion_batch WHERE batch_id='$same_id'::uuid")" -eq 1
assert_true 'concurrent identical uploads create one outbox event' test "$(db_scalar "SELECT COUNT(*) FROM outbox_event WHERE batch_id='$same_id'::uuid")" -eq 1

make_batch "$same_id" "$(new_uuid)" 22.5 degC "$temp_dir/different.gz"
conflict_code="$(upload "$temp_dir/different.gz")"
assert_true 'same batch ID with different bytes returns a deterministic checksum conflict' test "$conflict_code" -eq 409

orphan_id="$(new_uuid)"
make_batch "$orphan_id" "$(new_uuid)" 23.5 degC "$temp_dir/orphan.gz"
injected_code="$(upload "$temp_dir/orphan.gz" 'X-Lab-Fail-After-Raw-Store: true')"
assert_true 'injected failure occurs after raw persistence and before manifest commit' test "$injected_code" -eq 500
assert_true 'injected raw-write crash window leaves no partial manifest' test "$(db_scalar "SELECT COUNT(*) FROM ingestion_batch WHERE batch_id='$orphan_id'::uuid")" -eq 0
reconciliation="$(curl --fail --silent --header "Authorization: Bearer $token" http://localhost:8080/api/v1/admin/raw-objects/reconciliation)"
assert_true 'reconciliation exposes the orphan object' grep -q "$orphan_id" <<<"$reconciliation"
assert_true 'same-byte retry repairs the orphaned raw write' test "$(upload "$temp_dir/orphan.gz")" -eq 202
assert_true 'orphan repair commits one manifest' test "$(db_scalar "SELECT COUNT(*) FROM ingestion_batch WHERE batch_id='$orphan_id'::uuid")" -eq 1

rejected_id="$(new_uuid)"
make_batch "$rejected_id" "$(new_uuid)" 24.5 invalid-unit "$temp_dir/rejected.gz"
wait_until 'outbox drains before confirm-gap injection' 60 outbox_empty
curl --fail --silent --request PUT --header "Authorization: Bearer $token" --header 'Content-Type: application/json' --data "{\"armed\":true,\"batchId\":\"$rejected_id\"}" http://localhost:8080/api/v1/admin/faults/outbox-confirm-gap >/dev/null
assert_true 'confirm-gap probe reaches the gateway durability boundary' test "$(upload "$temp_dir/rejected.gz")" -eq 202
processed_twice() { [[ "$(db_scalar "SELECT processing_attempt_count FROM ingestion_batch WHERE batch_id='$rejected_id'::uuid")" -ge 2 ]]; }
wait_until 'confirmed-but-uncommitted outbox event is republished and processed twice' 90 processed_twice
assert_true 'duplicate rejected delivery does not multiply the rejection audit row' test "$(db_scalar "SELECT COUNT(*) FROM telemetry_rejection WHERE batch_id='$rejected_id'::uuid AND reason_code='UNSUPPORTED_RAW_UNIT'")" -eq 1
assert_true 'outbox event eventually records a successful confirmed publication' test "$(db_scalar "SELECT COUNT(*) FROM outbox_event WHERE batch_id='$rejected_id'::uuid AND published_at IS NOT NULL")" -eq 1
final_reconciliation="$(curl --fail --silent --header "Authorization: Bearer $token" http://localhost:8080/api/v1/admin/raw-objects/reconciliation)"
assert_true 'raw-object reconciliation is healthy after crash-window repair' grep -q '"healthy":true' <<<"$final_reconciliation"
scenario_pass 'ingestion boundaries'
