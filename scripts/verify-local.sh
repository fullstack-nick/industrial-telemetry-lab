#!/usr/bin/env bash

set -euo pipefail
[[ -z "${1:-}" ]] || export TELEMETRY_ENV_FILE="$1"
skip_scenarios="${SKIP_SCENARIOS:-false}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/lib/common.sh"
cd "$TELEMETRY_REPO_ROOT"

"$script_dir/check-prerequisites.sh"
printf 'Running the Maven unit, formatting, SpotBugs, and coverage gates...\n'
./mvnw --no-transfer-progress verify
compose config --quiet
compose --profile observability config --quiet
for dashboard in observability/grafana/dashboards/*.json; do
  grep -q '"title"' "$dashboard"
done
printf 'ASSERT PASS: Compose and dashboard artifacts parse cleanly\n'
compose up -d --build
wait_core_stack
"$script_dir/verify-openapi.sh"
if [[ "$skip_scenarios" != true ]]; then
  "$script_dir/run-end-to-end-tests.sh" "$TELEMETRY_ENV_FILE"
fi
printf 'Local verification passed.\n'
