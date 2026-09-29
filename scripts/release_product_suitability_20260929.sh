#!/usr/bin/env bash
# Single-host mounted-JAR rollout. Keep the originals and full DB backup for rollback.
set -euo pipefail

root=/opt/smart-assistant/releases/product-suitability-20260929
intake_current=/opt/smart-assistant/releases/data-intake-20260927/data-intake.jar
product_current=/opt/smart-assistant/releases/product-search-recall-20260929/smart-assistant-product-1.0.0-SNAPSHOT.jar
intake_candidate="$root/smart-assistant-data-intake-1.0.0-SNAPSHOT.jar"
product_candidate="$root/smart-assistant-product-1.0.0-SNAPSHOT.jar"
intake_expected=58f8551f3e7855d34bc1466faa3a5636227b5b91ddacd2241dc907397a3ffc29
product_expected=d5f154c1213f698d9128f05791d27f7a147c6c2d1eebd822a60fda3c4eec857e

hash() { sha256sum "$1" | cut -d ' ' -f 1; }
require() { if ! "$@"; then echo PREFLIGHT_FAILED >&2; exit 1; fi; }
mounted() {
  docker inspect --format '{{range .Mounts}}{{if eq .Destination "/app/app.jar"}}{{.Source}}{{end}}{{end}}' "$1"
}
ready() {
  local service=$1 port=$2
  for _ in $(seq 1 60); do
    if docker exec "$service" wget -qO- "http://localhost:$port/actuator/health/readiness" 2>/dev/null | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 2
  done
  return 1
}
public_ready() {
  for _ in $(seq 1 30); do
    if curl -fsS --max-time 5 https://xiaoyuai.cloud/healthz 2>/dev/null | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 2
  done
  return 1
}
drain() {
  local active
  for _ in $(seq 1 45); do
    active=$(docker exec -i smart-postgres sh -c 'exec psql --no-psqlrc -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
      <<< "SELECT count(*) FROM conversation_session_state WHERE status='ACTIVE_RUNNING';")
    [[ $active == 0 ]] && return 0
    sleep 2
  done
  echo 'Active conversations did not drain' >&2
  return 1
}

[[ ${1:-} == preflight || ${1:-} == deploy ]] || { echo 'Use preflight or deploy' >&2; exit 2; }
[[ -f "$root/before.dump" && -f "$root/migration.sql" ]] || { echo 'Backup or migration missing' >&2; exit 1; }
[[ $(hash "$intake_candidate") == "$intake_expected" ]] || { echo 'Intake hash mismatch' >&2; exit 1; }
[[ $(hash "$product_candidate") == "$product_expected" ]] || { echo 'Product hash mismatch' >&2; exit 1; }
[[ $(mounted smart-data-intake) == "$intake_current" ]] || { echo 'Intake mount drift' >&2; exit 1; }
[[ $(mounted smart-product) == "$product_current" ]] || { echo 'Product mount drift' >&2; exit 1; }
[[ $(docker exec smart-data-intake sha256sum /app/app.jar | cut -d ' ' -f 1) == $(hash "$intake_current") ]] || { echo 'Intake running artifact drift' >&2; exit 1; }
[[ $(docker exec smart-product sha256sum /app/app.jar | cut -d ' ' -f 1) == $(hash "$product_current") ]] || { echo 'Product running artifact drift' >&2; exit 1; }
ready smart-data-intake 8092
ready smart-product 8084
public_ready
[[ ! -e "$root/deployment.done" ]] || { echo 'Already deployed' >&2; exit 1; }
echo PREFLIGHT_OK
[[ $1 == deploy ]] || exit 0

[[ ! -e "$root/intake-before.jar" && ! -e "$root/product-before.jar" ]] || { echo 'Backup path occupied' >&2; exit 1; }
cp -p "$intake_current" "$root/intake-before.jar"
cp -p "$product_current" "$root/product-before.jar"
[[ $(hash "$root/intake-before.jar") == $(hash "$intake_current") ]] || exit 1
[[ $(hash "$root/product-before.jar") == $(hash "$product_current") ]] || exit 1
chmod 600 "$root/intake-before.jar" "$root/product-before.jar"

armed=1
rollback() {
  local status=$?
  trap - EXIT
  if [[ $status -ne 0 && $armed -eq 1 ]]; then
    echo ROLLING_BACK >&2
    docker stop --time 45 smart-gateway smart-data-intake smart-product >/dev/null 2>&1 || true
    cp -p "$root/intake-before.jar" "$intake_current"
    cp -p "$root/product-before.jar" "$product_current"
    docker start smart-data-intake smart-product smart-gateway >/dev/null
    ready smart-data-intake 8092 && ready smart-product 8084 && public_ready && echo ROLLBACK_HEALTHY >&2 || echo ROLLBACK_NEEDS_ATTENTION >&2
  fi
  exit "$status"
}
trap rollback EXIT

docker stop --time 45 smart-gateway >/dev/null
drain
docker stop --time 45 smart-data-intake smart-product >/dev/null
cp -p "$intake_candidate" "$intake_current"
cp -p "$product_candidate" "$product_current"
docker start smart-data-intake smart-product >/dev/null
ready smart-data-intake 8092
ready smart-product 8084
[[ $(docker exec smart-data-intake sha256sum /app/app.jar | cut -d ' ' -f 1) == "$intake_expected" ]]
[[ $(docker exec smart-product sha256sum /app/app.jar | cut -d ' ' -f 1) == "$product_expected" ]]
docker start smart-gateway >/dev/null
public_ready
touch "$root/deployment.done"
armed=0
echo DEPLOYED
