#!/usr/bin/env bash
set -euo pipefail

service_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

fail() {
  printf 'purity violation: %s\n' "$1" >&2
  exit 1
}

command -v rg >/dev/null 2>&1 || fail "ripgrep (rg) is required"

if find "$service_root" -path '*/build' -prune -o -name '*.proto' -type f -print | grep -q .; then
  fail "copied proto found under execution-service"
fi

source_files=()
while IFS= read -r source_file; do
  source_files+=("$source_file")
done < <(find "$service_root" -path '*/build' -prune -o \( -name '*.kt' -o -name '*.java' \) -type f -print)

if ((${#source_files[@]} > 0)); then
  if rg -n '(^|[[:space:]])import[[:space:]]+(cc\.monomer\.control|org\.springframework|jakarta\.persistence|javax\.persistence|com\.zaxxer\.hikari)' "${source_files[@]}"; then
    fail "Control or persistence framework imported"
  fi
fi

gradle_files=()
while IFS= read -r gradle_file; do
  gradle_files+=("$gradle_file")
done < <(
  find "$service_root" -path '*/build' -prune -o \
    \( -name '*.gradle' -o -name '*.gradle.kts' -o -name 'settings.gradle.kts' \) \
    -type f ! -path "$service_root/build.gradle.kts" -print
)

if rg -n 'includeBuild|control-service|cc\.monomer\.control|springframework|HikariCP|flyway|liquibase' "${gradle_files[@]}"; then
  fail "forbidden build or persistence dependency found"
fi

while IFS= read -r dependency_line; do
  if [[ "$dependency_line" == *testcontainers-postgresql* ]]; then
    continue
  fi
  dependency_file="${dependency_line%%:*}"
  case "$dependency_file" in
    "$service_root/driver-postgresql/"*) ;;
    *) fail "PostgreSQL dependency is allowed only in driver-postgresql: $dependency_line" ;;
  esac
done < <(rg -n 'org\.postgresql|postgresql:[^"]+' "${gradle_files[@]}" || true)

if find "$service_root" -path '*/build' -prune -o \
    \( -iname '*migration*' -o -iname '*schema*.sql' -o -iname 'application*.yml' -o -iname 'application*.yaml' -o -iname 'application*.properties' \) \
    -type f -print | grep -q .; then
  fail "migration, DDL, or internal database configuration found"
fi

printf 'execution purity verified\n'
