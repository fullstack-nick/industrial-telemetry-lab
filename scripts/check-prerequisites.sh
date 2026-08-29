#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
failures=0

report_check() {
  local name="$1" passed="$2" detail="$3"
  if [[ "$passed" == true ]]; then
    printf 'PASS  %-24s %s\n' "$name" "$detail"
  else
    printf 'FAIL  %-24s %s\n' "$name" "$detail" >&2
    failures=$((failures + 1))
  fi
}

java_output="$(java -version 2>&1 || true)"
if grep -q 'version "21\.' <<<"$java_output"; then
  report_check 'Java 21' true "$(head -n 1 <<<"$java_output")"
else
  report_check 'Java 21' false 'Install a Java 21 JDK and place java on PATH.'
fi

docker_version="$(docker info --format '{{.ServerVersion}}' 2>/dev/null || true)"
report_check 'Docker daemon' "$([[ -n "$docker_version" ]] && echo true || echo false)" "${docker_version:-Start Docker Desktop or the Docker daemon.}"

compose_version="$(docker compose version --short 2>/dev/null || true)"
report_check 'Docker Compose v2' "$([[ -n "$compose_version" ]] && echo true || echo false)" "${compose_version:-Install the Docker Compose v2 plugin.}"

git_version="$(git --version 2>/dev/null || true)"
report_check 'Git' "$([[ -n "$git_version" ]] && echo true || echo false)" "${git_version:-Install Git.}"
report_check 'Maven Wrapper' "$([[ -x "$repo_root/mvnw" ]] && echo true || echo false)" 'repository wrapper'

free_kib="$(df -Pk "$repo_root" | awk 'NR==2 {print $4}')"
free_gib=$((free_kib / 1024 / 1024))
report_check 'Free disk' "$([[ "$free_gib" -ge 15 ]] && echo true || echo false)" "$free_gib GiB free; 15 GiB required"

env_file="$repo_root/.env"
[[ -f "$env_file" ]] || env_file="$repo_root/.env.example"
project_ports="$(docker compose --env-file "$env_file" ps 2>/dev/null || true)"
if command -v ss >/dev/null 2>&1; then
  listeners="$(ss -ltn 2>/dev/null || true)"
  for port in 3000 3100 3200 5432 5672 8080 8081 8082 8083 8333 8888 9090 9333 12345 15672; do
    if grep -Eq "[:.]${port}[[:space:]]" <<<"$listeners" && ! grep -q "127.0.0.1:${port}->" <<<"$project_ports"; then
      report_check "Port $port" false 'occupied by another process'
    else
      report_check "Port $port" true 'available or used by this Compose project'
    fi
  done
else
  printf 'INFO: ss is unavailable; Compose will perform the final port-conflict check.\n'
fi

if (( failures > 0 )); then
  printf '\nCorrect the failed prerequisites above; this script did not modify the host.\n' >&2
  exit 1
fi
printf 'All prerequisites are ready. No host changes were made.\n'
