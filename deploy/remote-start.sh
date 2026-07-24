#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/smart-assistant}"
cd "$APP_DIR"

set -a
source deploy/.env
set +a

JAVA_IMAGE="docker.m.daocloud.io/eclipse-temurin:21-jre-alpine"
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

  if [[ "$service" == "gateway" ]]; then
    extra_args+=("--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration")
  elif [[ "$service" == "consumer" ]]; then
    extra_args+=("--spring.autoconfigure.exclude=io.opentelemetry.instrumentation.spring.autoconfigure.internal.instrumentation.web.SpringWebInstrumentationAutoConfiguration,io.opentelemetry.instrumentation.spring.autoconfigure.internal.instrumentation.web.RestClientInstrumentationAutoConfiguration,io.opentelemetry.instrumentation.spring.autoconfigure.internal.instrumentation.webflux.SpringWebfluxInstrumentationAutoConfiguration")
    extra_args+=("--spring.aop.auto=false")
    extra_args+=("--spring.ai.mcp.server.enabled=false")
    extra_args+=("--spring.ai.mcp.client.enabled=false")
    local router_ip
    router_ip="$(docker inspect -f '{{(index .NetworkSettings.Networks "smart-network").IPAddress}}' smart-router)"
    test -n "$router_ip"
    extra_docker_args+=("--add-host" "smart-router:${router_ip}")
    extra_docker_args+=("-e" "ROUTER_SERVICE_URL=http://smart-router:8083")
  elif [[ "$service" == "router" ]]; then
    extra_args+=("--spring.autoconfigure.exclude=io.opentelemetry.instrumentation.spring.autoconfigure.internal.instrumentation.web.SpringWebInstrumentationAutoConfiguration,io.opentelemetry.instrumentation.spring.autoconfigure.internal.instrumentation.web.RestClientInstrumentationAutoConfiguration,io.opentelemetry.instrumentation.spring.autoconfigure.internal.instrumentation.webflux.SpringWebfluxInstrumentationAutoConfiguration")
    extra_args+=("--spring.aop.auto=false")
    extra_args+=("--spring.ai.mcp.server.enabled=false")
    extra_args+=("--spring.ai.mcp.client.enabled=false")
    local ollama_ip
    ollama_ip="$(docker inspect -f '{{(index .NetworkSettings.Networks "smart-network").IPAddress}}' smart-ollama)"
    test -n "$ollama_ip"
    extra_docker_args+=("--add-host" "smart-ollama:${ollama_ip}")
  elif [[ "$service" == "general" ]]; then
    # GeneralAgentConfig intentionally injects the bean named
    # deepSeekChatModel. Point that OpenAI-compatible client at the local
    # Ollama API so the public deployment does not depend on a cloud key.
    extra_args+=("--spring.autoconfigure.exclude=org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration")
    extra_args+=("--spring.ai.mcp.server.enabled=false")
    extra_args+=("--spring.ai.mcp.client.enabled=false")
    extra_docker_args+=("-e" "SPRING_AI_DEEPSEEK_BASE_URL=http://smart-ollama:11434/v1")
    extra_docker_args+=("-e" "SPRING_AI_DEEPSEEK_API_KEY=ollama")
    extra_docker_args+=("-e" "SPRING_AI_DEEPSEEK_CHAT_OPTIONS_MODEL=qwen2.5:7b")
  elif [[ "$service" == "order" ]]; then
    # DeepSeekChatAutoConfiguration requires the RetryTemplate created by
    # SpringAiRetryAutoConfiguration. OrderIntentService also requires the
    # Ollama-backed lightChatModel, so keep both auto-configurations enabled.
    extra_args+=("--spring.ai.mcp.server.enabled=false")
    extra_args+=("--spring.ai.mcp.client.enabled=false")
  else
    extra_args+=("--spring.autoconfigure.exclude=org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration,org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration")
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
    -e "SPRING_AI_OLLAMA_BASE_URL=http://smart-ollama:11434" \
    -e "MANAGEMENT_TRACING_ZIPKIN_TRACING_ENDPOINT=http://smart-zipkin:9411/api/v2/spans" \
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

# Recreate Ollama without a host port while retaining downloaded models.
if docker container inspect ollama-predeploy >/dev/null 2>&1 \
  && ! docker container inspect smart-ollama >/dev/null 2>&1; then
  docker run -d \
    --name smart-ollama \
    --network "$NETWORK" \
    --restart unless-stopped \
    -v ollama-data:/root/.ollama \
    docker.m.daocloud.io/ollama/ollama:latest >/dev/null
fi

start_service gateway 8081
start_service user 8086
start_service router 8083
start_service consumer 8082
start_service order 8085
start_service product 8084
start_service general 8087
start_service embedding-service 8091

restart_nginx

echo "CONTAINERS_STARTED"
