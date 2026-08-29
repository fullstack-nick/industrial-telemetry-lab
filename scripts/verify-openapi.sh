#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
snapshot="${1:-$repo_root/contracts/openapi/telemetry-api-v1.json}"
runtime="$(mktemp)"
trap 'rm -f "$runtime"' EXIT
curl --fail --silent --show-error http://localhost:8080/v3/api-docs >"$runtime"
if ! cmp --silent "$runtime" "$snapshot"; then
  printf 'OpenAPI drift detected. Review the API change, then run scripts/export-openapi.sh to update the approved snapshot.\n' >&2
  diff --unified "$snapshot" "$runtime" || true
  exit 1
fi
printf 'ASSERT PASS: runtime OpenAPI exactly matches contracts/openapi/telemetry-api-v1.json\n'
