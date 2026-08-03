#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/smart-assistant}"
cd "$APP_DIR"

set -a
source deploy/.env
set +a

# ONNX Runtime depends on libstdc++. The Debian-based Temurin image includes
# that native runtime; the Alpine variant does not.
JAVA_IMAGE="docker.m.daocloud.io/eclipse-temurin:21-jre"
NETWORK="smart-network"
NACOS_IP="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' smart-nacos)"
test -n "$NACOS_IP"
POSTGRES_IP="$(docker inspect -f '{{(index .NetworkSettings.Networks "smart-network").IPAddress}}' smart-postgres)"
REDIS_IP="$(docker inspect -f '{{(index .NetworkSettings.Networks "smart-network").IPAddress}}' smart-redis)"
test -n "$POSTGRES_IP"
test -n "$REDIS_IP"

docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create "$NETWORK" >/dev/null

replace_container() {
  local name="$1"
  if docker container inspect "$name" >/dev/null 2>&1; then
    docker rm -f "$name" >/dev/null
  fi
}

start_service() {
  local service="$1"
  local port="$2"
  local module="smart-assistant-${service}"
  local jar="${APP_DIR}/${module}/target/${module}-1.0.0-SNAPSHOT.jar"
  local extra_args=()
  local extra_docker_args=()

  case "$service" in
    consumer|router|general|order|product)
      if [[ -z "${DEEPSEEK_API_KEY:-}" ]]; then
        echo "DEEPSEEK_API_KEY must be set in deploy/.env for ${service}" >&2
        return 1
      fi
      ;;
  esac

  if [[ "$service" == "gateway" ]]; then
    extra_args+=("--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration")
  elif [[ "$service" == "consumer" ]]; then
    extra_args+=("--spring.autoconfigure.exclude=io.opentelemetry.instrumentation.spring.autoconfigure.internal.instrumentation.web.SpringWebInstrumentationAutoConfiguration,io.opentelemetry.instrumentation.spring.autoconfigure.internal.instrumentation.web.RestClientInstrumentationAutoConfiguration,io.opentelemetry.instrumentation.spring.autoconfigure.internal.instrumentation.webflux.SpringWebfluxInstrumentationAutoConfiguration")
    extra_args+=("--spring.aop.auto=false")
    extra_args+=("--spring.ai.mcp.server.enabled=false")
    extra_args+=("--spring.ai.mcp.client.enabled=false")
    # Use the network DNS name instead of pinning Router's current container IP.
    # A Router restart may assign a new IP, while smart-router remains stable.
    extra_docker_args+=("-e" "ROUTER_SERVICE_URL=http://smart-router:8083")
  elif [[ "$service" == "router" ]]; then
    extra_args+=("--spring.autoconfigure.exclude=io.opentelemetry.instrumentation.spring.autoconfigure.internal.instrumentation.web.SpringWebInstrumentationAutoConfiguration,io.opentelemetry.instrumentation.spring.autoconfigure.internal.instrumentation.web.RestClientInstrumentationAutoConfiguration,io.opentelemetry.instrumentation.spring.autoconfigure.internal.instrumentation.webflux.SpringWebfluxInstrumentationAutoConfiguration")
    extra_args+=("--spring.aop.auto=false")
    extra_args+=("--spring.ai.mcp.server.enabled=false")
    extra_args+=("--spring.ai.mcp.client.enabled=false")
  elif [[ "$service" == "general" ]]; then
    extra_args+=("--spring.ai.mcp.server.enabled=false")
    extra_args+=("--spring.ai.mcp.client.enabled=false")
  elif [[ "$service" == "order" ]]; then
    extra_args+=("--spring.ai.mcp.server.enabled=false")
    extra_args+=("--spring.ai.mcp.client.enabled=false")
    extra_docker_args+=("-e" "APP_RAG_STORE_MODE=pg")
    extra_docker_args+=("-e" "EMBEDDING_SERVICE_URL=http://smart-embedding-service:8091")
  elif [[ "$service" == "product" ]]; then
    extra_args+=("--spring.ai.mcp.server.enabled=false")
    extra_args+=("--spring.ai.mcp.client.enabled=false")
    extra_docker_args+=("-e" "EMBEDDING_SERVICE_URL=http://smart-embedding-service:8091")
    extra_docker_args+=("-e" "APP_RAG_STORE_MODE=pg")
    extra_docker_args+=("-e" "DATASOURCE_URL=jdbc:postgresql://${POSTGRES_IP}:5432/${POSTGRES_DB:-a2a_system}")
    extra_docker_args+=("-e" "DATASOURCE_USERNAME=${POSTGRES_USER:-postgres}")
    extra_docker_args+=("-e" "DATASOURCE_PASSWORD=${POSTGRES_PASSWORD}")
  elif [[ "$service" == "embedding-service" ]]; then
    extra_docker_args+=("-v" "${APP_DIR}/models:/app/models:ro")
    extra_docker_args+=("-e" "BGE_MODEL_PATH=/app/models/bge-large-zh-v1.5.onnx")
    extra_docker_args+=("-e" "BGE_VOCAB_PATH=/app/models/tokenizer.json")
  else
    extra_args+=("--spring.autoconfigure.exclude=org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration")
  fi

  test -s "$jar"
  if docker container inspect "smart-${service}" >/dev/null 2>&1; then
    if [[ "$(docker inspect -f '{{.State.Running}}' "smart-${service}")" == "true" ]]; then
      echo "smart-${service} already running"
      return
    fi
    replace_container "smart-${service}"
  fi

  docker run -d \
    --name "smart-${service}" \
    --network "$NETWORK" \
    --restart unless-stopped \
    -v "${jar}:/app/app.jar:ro" \
    -e "PORT=${port}" \
    -e "JWT_SECRET=${JWT_SECRET}" \
    -e "DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY:-}" \
    -e "DEEPSEEK_BASE_URL=${DEEPSEEK_BASE_URL:-https://api.deepseek.com}" \
    -e "DEEPSEEK_CHAT_MODEL=${DEEPSEEK_CHAT_MODEL:-deepseek-v4-flash}" \
    -e "DEEPSEEK_LIGHT_MODEL=${DEEPSEEK_LIGHT_MODEL:-deepseek-v4-flash}" \
    -e "DEEPSEEK_REASONING_MODEL=${DEEPSEEK_REASONING_MODEL:-deepseek-v4-pro}" \
    -e "POSTGRES_PASSWORD=${POSTGRES_PASSWORD}" \
    -e "REDIS_PASSWORD=${REDIS_PASSWORD}" \
    -e "NACOS_PASSWORD=${NACOS_PASSWORD}" \
    -e "SPRING_DATASOURCE_URL=jdbc:postgresql://${POSTGRES_IP}:5432/${POSTGRES_DB:-a2a_system}" \
    -e "SPRING_DATASOURCE_USERNAME=${POSTGRES_USER:-postgres}" \
    -e "SPRING_DATASOURCE_PASSWORD=${POSTGRES_PASSWORD}" \
    -e "SPRING_DATA_REDIS_HOST=${REDIS_IP}" \
    -e "SPRING_DATA_REDIS_PASSWORD=${REDIS_PASSWORD}" \
    -e "SPRING_MAIN_ALLOW_BEAN_DEFINITION_OVERRIDING=true" \
    -e "CORS_ALLOWED_ORIGINS=http://123.56.6.102,http://localhost:3000,http://localhost:5173" \
    -e "SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR=${NACOS_IP}:8848" \
    -e "SPRING_CLOUD_NACOS_DISCOVERY_USERNAME=${NACOS_USERNAME:-nacos}" \
    -e "SPRING_CLOUD_NACOS_DISCOVERY_PASSWORD=${NACOS_PASSWORD}" \
    -e "MANAGEMENT_TRACING_ZIPKIN_TRACING_ENDPOINT=http://smart-zipkin:9411/api/v2/spans" \
    -e "OTEL_TRACES_EXPORTER=none" \
    -e "OTEL_METRICS_EXPORTER=none" \
    -e "OTEL_LOGS_EXPORTER=none" \
    "${extra_docker_args[@]}" \
    "$JAVA_IMAGE" \
    java -Dfile.encoding=UTF-8 -Xmx512m -Xms256m -jar /app/app.jar \
      "--server.port=${port}" \
      "${extra_args[@]}"
}

restart_nginx() {
  replace_container smart-nginx
  local gateway_ip
  local consumer_ip
  gateway_ip="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' smart-gateway)"
  consumer_ip="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' smart-consumer)"
  test -n "$gateway_ip"
  test -n "$consumer_ip"
  docker run -d \
    --name smart-nginx \
    --network "$NETWORK" \
    --restart unless-stopped \
    -p 80:80 \
    --add-host "smart-gateway:${gateway_ip}" \
    --add-host "smart-consumer:${consumer_ip}" \
    -v "${APP_DIR}/deploy/nginx/default.conf:/etc/nginx/conf.d/default.conf:ro" \
    -v "${APP_DIR}/frontend/dist:/usr/share/nginx/html:ro" \
    nginx:1.27-alpine >/dev/null
}

if [[ "${1:-}" == "user-only" ]]; then
  start_service user 8086
  exit 0
fi

if [[ "${1:-}" == "gateway-only" ]]; then
  start_service gateway 8081
  exit 0
fi

if [[ "${1:-}" == "consumer-only" ]]; then
  start_service consumer 8082
  restart_nginx
  exit 0
fi

if [[ "${1:-}" == "router-only" ]]; then
  start_service router 8083
  exit 0
fi

if [[ "${1:-}" == "general-only" ]]; then
  start_service general 8087
  exit 0
fi

if [[ "${1:-}" == "order-only" ]]; then
  start_service order 8085
  exit 0
fi

if [[ "${1:-}" == "product-only" ]]; then
  start_service product 8084
  exit 0
fi

if [[ "${1:-}" == "embedding-only" ]]; then
  start_service embedding-service 8091
  exit 0
fi

start_service gateway 8081
start_service user 8086
start_service router 8083
start_service consumer 8082
start_service embedding-service 8091
start_service order 8085
start_service product 8084
start_service general 8087

restart_nginx

echo "CONTAINERS_STARTED"
