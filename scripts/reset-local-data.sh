#!/usr/bin/env bash

set -euo pipefail
force=false
env_argument=""
for argument in "$@"; do
  case "$argument" in
    --force) force=true ;;
    *) env_argument="$argument" ;;
  esac
done
[[ -z "$env_argument" ]] || export TELEMETRY_ENV_FILE="$env_argument"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"
cd "$TELEMETRY_REPO_ROOT"
project="$(env_value COMPOSE_PROJECT_NAME industrial-telemetry-lab)"
mapfile -t volumes < <(docker volume ls --filter "label=com.docker.compose.project=$project" --format '{{.Name}}')
printf 'The following project-scoped Docker volumes will be permanently removed:\n'
if (( ${#volumes[@]} == 0 )); then
  printf '  (none currently exist)\n'
else
  printf '  %s\n' "${volumes[@]}"
fi
if [[ "$force" != true ]]; then
  read -r -p 'Type RESET to continue: ' answer
  if [[ "$answer" != RESET ]]; then
    printf 'Reset cancelled.\n'
    exit 0
  fi
fi
compose down --volumes --remove-orphans
printf "Removed only containers, networks, and named volumes owned by Compose project '%s'. This data is not recoverable.\n" "$project"
