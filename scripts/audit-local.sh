#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
env_file="${TELEMETRY_ENV_FILE:-${1:-}}"
[[ -n "$env_file" ]] || env_file="$repo_root/.env"
[[ -f "$env_file" ]] || env_file="$repo_root/.env.example"
skip_vulnerability_scan="${SKIP_VULNERABILITY_SCAN:-false}"
skip_image_scan="${SKIP_IMAGE_SCAN:-false}"
report_directory="$repo_root/target/security"
mkdir -p "$report_directory"

cd "$repo_root"
printf 'Generating the aggregate CycloneDX SBOM...\n'
./mvnw --no-transfer-progress -Psbom -DskipTests verify
[[ -f target/bom.json ]] || { printf 'Expected aggregate SBOM was not created at target/bom.json.\n' >&2; exit 1; }
printf 'ASSERT PASS: aggregate SBOM created at target/bom.json\n'

if [[ "$skip_vulnerability_scan" == true ]]; then
  printf 'SKIP: OWASP Dependency-Check was explicitly disabled.\n'
elif [[ -z "${NVD_API_KEY:-}" ]]; then
  printf 'SKIP: OWASP Dependency-Check requires NVD_API_KEY in the process environment; the SBOM and image audit will continue.\n'
else
  printf 'Running OWASP Dependency-Check (the first vulnerability-data download can be slow)...\n'
  ./mvnw --no-transfer-progress -Psecurity-audit -DskipTests verify
  printf 'ASSERT PASS: dependency vulnerability policy passed\n'
fi

printf 'Recording configured container tags and locally resolved immutable IDs...\n'
mapfile -t images < <(
  {
    docker compose --env-file "$env_file" --profile observability config --images
    awk '/^[[:space:]]*FROM[[:space:]]+/ { print $2 }' docker/app.Dockerfile
  } | sort -u
)
(( ${#images[@]} > 0 )) || { printf 'Could not resolve Compose image references.\n' >&2; exit 1; }
{
  printf '[\n'
  separator=''
  for reference in "${images[@]}"; do
    printf '%s' "$separator"
    if inspection="$(docker image inspect "$reference" --format '{{json .}}' 2>/dev/null)"; then
      image_id="$(sed -n 's/.*"Id":"\([^"]*\)".*/\1/p' <<<"$inspection")"
      digest="$(sed -n 's/.*"RepoDigests":\["\([^"]*\)".*/\1/p' <<<"$inspection")"
      printf '  {"reference":"%s","imageId":"%s","firstRepositoryDigest":"%s","locallyPresent":true}' "$reference" "$image_id" "$digest"
    else
      printf '  {"reference":"%s","imageId":null,"firstRepositoryDigest":null,"locallyPresent":false}' "$reference"
    fi
    separator=$',\n'
  done
  printf '\n]\n'
} >"$report_directory/container-images.json"
printf 'ASSERT PASS: container inventory created at target/security/container-images.json\n'

if [[ "$skip_image_scan" == true ]]; then
  printf 'SKIP: Docker Scout image scan was explicitly disabled.\n'
elif docker scout version >/dev/null 2>&1; then
  scout_directory="$report_directory/docker-scout"
  mkdir -p "$scout_directory"
  scout_completed=0
  scout_skipped_for_authentication=false
  for reference in "${images[@]}"; do
    safe_name="$(tr -c 'A-Za-z0-9_.-' '_' <<<"$reference")"
    if scout_output="$(docker scout cves --format sarif --output "$scout_directory/${safe_name}.sarif" "$reference" 2>&1)"; then
      [[ -z "$scout_output" ]] || printf '%s\n' "$scout_output"
      ((scout_completed += 1))
    elif grep -Eqi 'log in with your Docker ID|docker login|authentication required|unauthorized' <<<"$scout_output"; then
      printf '%s\n' "$scout_output"
      printf 'SKIP: Docker Scout requires an authenticated Docker session; container inventory remains available and any partial Scout reports are not a complete audit.\n'
      scout_skipped_for_authentication=true
      break
    else
      printf '%s\n' "$scout_output" >&2
      printf 'Docker Scout failed for %s.\n' "$reference" >&2
      exit 1
    fi
  done
  if [[ "$scout_skipped_for_authentication" == false ]]; then
    printf 'ASSERT PASS: Docker Scout reports created for %s images under target/security/docker-scout\n' "$scout_completed"
  fi
else
  printf 'SKIP: Docker Scout is not installed; container IDs were still recorded.\n'
fi

printf 'Local dependency and image audit completed.\n'
