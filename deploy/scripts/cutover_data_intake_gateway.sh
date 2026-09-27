#!/usr/bin/env bash
set -euo pipefail

release=/opt/smart-assistant/releases/data-intake-20260927
live=/opt/smart-assistant/releases/checkout-history-20260927/gateway.jar
candidate="$release/gateway-new.jar"
backup="$release/gateway-before.jar"

test -f "$candidate"
test -f "$backup"
test -f "$live"
test "$(docker inspect smart-data-intake --format '{{.State.Running}}')" = true
docker exec smart-data-intake wget -qO- http://127.0.0.1:8092/actuator/health/readiness | grep -q UP

restore() {
  docker stop --time 20 smart-gateway >/dev/null || true
  cp "$backup" "$live"
  docker start smart-gateway >/dev/null
  echo 'Gateway restored from pre-cutover backup' >&2
}

docker stop --time 20 smart-gateway >/dev/null
trap restore ERR
cp "$candidate" "$live"
docker start smart-gateway >/dev/null

healthy=false
for attempt in $(seq 1 35); do
  if docker exec smart-gateway wget -qO- http://127.0.0.1:8081/actuator/health/readiness 2>/dev/null | grep -q UP; then
    healthy=true
    break
  fi
  sleep 2
done
if [ "$healthy" != true ]; then
  restore
  trap - ERR
  exit 1
fi
trap - ERR
echo "Gateway ready after cutover; candidate SHA256: $(sha256sum "$live" | cut -d' ' -f1)"
