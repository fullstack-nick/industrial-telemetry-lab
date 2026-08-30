#!/usr/bin/env bash

set -euo pipefail
[[ -z "${1:-}" ]] || export TELEMETRY_ENV_FILE="$1"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib/common.sh"
cd "$TELEMETRY_REPO_ROOT"

cleanup() { reset_simulator_faults >/dev/null 2>&1 || true; }
trap cleanup EXIT

scenario_header unknown-tag-and-replay
start_core_stack
reset_simulator_faults
rejections_before="$(db_scalar "SELECT COUNT(*) FROM telemetry_rejection WHERE reason_code='UNKNOWN_SOURCE_TAG'")"
from_time="$(db_scalar "SELECT to_char(now() - interval '2 minutes', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')")"
set_simulator_faults '{"newUnknownTagEnabled":true}'
unknown_rejections_grew() { [[ "$(db_scalar "SELECT COUNT(*) FROM telemetry_rejection WHERE reason_code='UNKNOWN_SOURCE_TAG'")" -gt "$rejections_before" ]]; }
wait_until 'mapping 1.0 rejects the new auxiliary temperature tag' 50 unknown_rejections_grew
reset_simulator_faults
to_time="$(db_scalar "SELECT to_char(now() + interval '1 minute', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')")"
aux_before="$(db_scalar "SELECT COUNT(*) FROM telemetry_sample WHERE source_tag LIKE '%.TEMP_AUX_PV'")"
cursor_before_replay="$(collector_status | json_number sourceCursor)"
token="$(env_value LOCAL_API_TOKEN local-development-token-change-me)"
body="{\"facilityId\":\"facility-alpha\",\"from\":\"$from_time\",\"to\":\"$to_time\",\"mappingVersion\":\"controller-a-mapping-1.1.0\",\"qualityRulesVersion\":\"quality-rules-1.0.0\",\"reason\":\"Demonstrate recovery after installing the auxiliary-temperature mapping\"}"
create_replay() {
  curl --fail --silent --show-error --request POST \
    --header "Authorization: Bearer $token" \
    --header 'Content-Type: application/json' \
    --data "$body" \
    http://localhost:8080/api/v1/replays
}
first_response="$(create_replay)"
first_id="$(sed -nE 's/.*"replayId":"([^"]+)".*/\1/p' <<<"$first_response")"
[[ -n "$first_id" ]]
first_complete() { curl --fail --silent "http://localhost:8080/api/v1/replays/$first_id" | grep -q '"status":"COMPLETED"'; }
wait_until 'first replay completes with mapping 1.1' 150 first_complete
aux_after_first="$(db_scalar "SELECT COUNT(*) FROM telemetry_sample WHERE source_tag LIKE '%.TEMP_AUX_PV'")"
assert_true 'previously rejected auxiliary observations become canonical' test "$aux_after_first" -gt "$aux_before"

second_response="$(create_replay)"
second_id="$(sed -nE 's/.*"replayId":"([^"]+)".*/\1/p' <<<"$second_response")"
[[ -n "$second_id" ]]
second_complete() { curl --fail --silent "http://localhost:8080/api/v1/replays/$second_id" | grep -q '"status":"COMPLETED"'; }
wait_until 'repeat replay completes' 150 second_complete
aux_after_second="$(db_scalar "SELECT COUNT(*) FROM telemetry_sample WHERE source_tag LIKE '%.TEMP_AUX_PV'")"
second_duplicate_count="$(db_scalar "SELECT duplicate_count FROM replay_run WHERE replay_id='$second_id'")"
assert_true 'repeat replay recognizes already-canonical observations as duplicates' test "$second_duplicate_count" -gt 0
duplicate_canonical_rows="$(db_scalar "SELECT COUNT(*) - COUNT(DISTINCT observation_id) FROM telemetry_sample WHERE source_tag LIKE '%.TEMP_AUX_PV'")"
assert_true 'repeating replay creates no duplicate canonical samples' test "$duplicate_canonical_rows" -eq 0
assert_true 'live source acquisition continues while replay work is processed' test "$(collector_status | json_number sourceCursor)" -gt "$cursor_before_replay"
printf 'Recovered auxiliary samples: %s\n' "$((aux_after_first - aux_before))"
printf 'Additional unique auxiliary samples found by repeat replay: %s\n' "$((aux_after_second - aux_after_first))"
scenario_pass unknown-tag-and-replay
