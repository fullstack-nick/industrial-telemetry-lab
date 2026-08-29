#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$repo_root/contracts/openapi"
curl --fail --silent --show-error http://localhost:8080/v3/api-docs >"$repo_root/contracts/openapi/telemetry-api-v1.json"
printf 'Updated %s\n' "$repo_root/contracts/openapi/telemetry-api-v1.json"
