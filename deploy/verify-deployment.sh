#!/usr/bin/env bash
set -u

NETWORK="${NETWORK:-smart-network}"
failures=0

check_service() {
  local name="$1"
  local port="$2"
  local state body

  state="$(docker inspect -f \
    '{{.State.Running}}|{{.State.Status}}|{{.RestartCount}}|{{.State.ExitCode}}' \
    "$name" 2>/dev/null || echo 'missing')"
  echo "${name}|${state}"
  if [[ "$state" != true\|* ]]; then
    failures=$((failures + 1))
    return
  fi

  if [[ "$name" == "smart-embedding-service" ]]; then
    body="$(docker exec "$name" curl -fsS --max-time 8 \
      "http://127.0.0.1:${port}/actuator/health" 2>/dev/null || true)"
  else
    body="$(docker exec "$name" wget -qO- \
      "http://127.0.0.1:${port}/actuator/health" 2>/dev/null || true)"
  fi
  body="$(printf '%s' "$body" | tr -d '\r\n' | head -c 500)"
  echo "${name}|${body}"
  if [[ "$body" != *'"status":"UP"'* ]]; then
    failures=$((failures + 1))
  fi
}

check_service smart-embedding-service 8091
check_service smart-gateway 8081
check_service smart-user 8086
check_service smart-router 8083
check_service smart-consumer 8082
check_service smart-order 8085
check_service smart-product 8084
check_service smart-general 8087

nginx_state="$(docker inspect -f \
  '{{.State.Running}}|{{.State.Status}}|{{.RestartCount}}|{{.State.ExitCode}}' \
  smart-nginx 2>/dev/null || echo 'missing')"
echo "smart-nginx|${nginx_state}"
if [[ "$nginx_state" != true\|* ]]; then
  failures=$((failures + 1))
fi

health_code="$(curl -sS --max-time 8 -o /tmp/smart-healthz.out \
  -w '%{http_code}' http://127.0.0.1/healthz 2>/dev/null || true)"
health_body="$(tr -d '\r\n' < /tmp/smart-healthz.out 2>/dev/null | head -c 500)"
rm -f /tmp/smart-healthz.out
echo "nginx-healthz|HTTP=${health_code}|${health_body}"
if [[ "$health_code" != "200" ]]; then
  failures=$((failures + 1))
fi

front_code="$(curl -sS --max-time 8 -o /tmp/smart-index.out \
  -w '%{http_code}' http://127.0.0.1/ 2>/dev/null || true)"
front_bytes="$(wc -c < /tmp/smart-index.out 2>/dev/null || echo 0)"
rm -f /tmp/smart-index.out
echo "nginx-index|HTTP=${front_code}|bytes=${front_bytes}"
if [[ "$front_code" != "200" || "$front_bytes" -lt 100 ]]; then
  failures=$((failures + 1))
fi

echo "FAILURES=${failures}"
exit "$failures"
