#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/smart-assistant}"
JAVA_IMAGE="${JAVA_IMAGE:-docker.m.daocloud.io/eclipse-temurin:21-jre-alpine}"
EMBEDDING_JAVA_IMAGE="${EMBEDDING_JAVA_IMAGE:-docker.m.daocloud.io/eclipse-temurin:21-jre}"
NETWORK="${NETWORK:-smart-network}"

cd "$APP_DIR"
set -a
source deploy/.env
set +a

: "${JWT_SECRET:?JWT_SECRET must be set in deploy/.env}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set in deploy/.env}"
: "${REDIS_PASSWORD:?REDIS_PASSWORD must be set in deploy/.env}"
: "${NACOS_PASSWORD:?NACOS_PASSWORD must be set in deploy/.env}"

EFFECTIVE_DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY:-}"
LOCAL_CHAT_ARGS=()
if [[ -z "$EFFECTIVE_DEEPSEEK_API_KEY" ]]; then
  EFFECTIVE_DEEPSEEK_API_KEY="ollama-local"
  LOCAL_CHAT_ARGS+=(
    "-e" "SPRING_AI_DEEPSEEK_BASE_URL=http://${OLLAMA_IP:-smart-ollama}:11434/v1"
    "-e" "SPRING_AI_DEEPSEEK_CHAT_OPTIONS_MODEL=qwen2.5:7b"
  )
fi

docker network inspect "$NETWORK" >/dev/null 2>&1 ||
  docker network create "$NETWORK" >/dev/null

container_ip() {
  docker inspect -f \
    "{{(index .NetworkSettings.Networks \"${NETWORK}\").IPAddress}}" "$1"
}

set_embedding_ip_and_wait() {
  local body=""
  local deadline=$((SECONDS + 240))

  EMBEDDING_IP="$(container_ip smart-embedding-service)"
  test -n "$EMBEDDING_IP"

  while (( SECONDS < deadline )); do
    body="$(curl -fsS --max-time 5 \
      "http://${EMBEDDING_IP}:8091/api/embedding/health" 2>/dev/null || true)"
    if [[ "$body" == *'"available":true'* ]]; then
      echo "smart-embedding-service is ready"
      return 0
    fi
    sleep 2
  done

  echo "smart-embedding-service did not become ready within 240s" >&2
  docker logs --tail 80 smart-embedding-service >&2 || true
  return 1
}

NACOS_IP="$(container_ip smart-nacos)"
POSTGRES_IP="$(container_ip smart-postgres)"
REDIS_IP="$(container_ip smart-redis)"
OLLAMA_IP="$(container_ip smart-ollama)"
ZIPKIN_IP="$(container_ip smart-zipkin)"

for required_ip in \
  "$NACOS_IP" "$POSTGRES_IP" "$REDIS_IP" "$OLLAMA_IP" "$ZIPKIN_IP"; do
  test -n "$required_ip"
done

if [[ "${LOCAL_CHAT_ARGS[*]:-}" == *"smart-ollama"* ]]; then
  LOCAL_CHAT_ARGS=(
    "-e" "SPRING_AI_DEEPSEEK_BASE_URL=http://${OLLAMA_IP}:11434/v1"
    "-e" "SPRING_AI_DEEPSEEK_CHAT_OPTIONS_MODEL=qwen2.5:7b"
  )
fi

replace_container() {
  local name="$1"
  if docker container inspect "$name" >/dev/null 2>&1; then
    docker rm -f "$name" >/dev/null
  fi
}

start_embedding() {
  local jar="${APP_DIR}/smart-assistant-embedding-service/target/smart-assistant-embedding-service-1.0.0-SNAPSHOT.jar"
  test -s "$jar"
  test -s "${APP_DIR}/models/bge-large-zh-v1.5.onnx"
  test -s "${APP_DIR}/models/bge-large-zh-v1.5.onnx.data"
  test -s "${APP_DIR}/models/tokenizer.json"

  replace_container smart-embedding-service
  docker run -d \
    --name smart-embedding-service \
    --network "$NETWORK" \
    --restart unless-stopped \
    --memory 3g \
    -v "${jar}:/app/app.jar:ro" \
    -v "${APP_DIR}/models:/app/models:ro" \
    -e "PORT=8091" \
    -e "NACOS_SERVER_ADDR=${NACOS_IP}:8848" \
    -e "SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR=${NACOS_IP}:8848" \
    -e "SPRING_CLOUD_NACOS_DISCOVERY_USERNAME=${NACOS_USERNAME:-nacos}" \
    -e "SPRING_CLOUD_NACOS_DISCOVERY_PASSWORD=${NACOS_PASSWORD}" \
    -e "BGE_MODEL_PATH=/app/models/bge-large-zh-v1.5.onnx" \
    -e "BGE_VOCAB_PATH=/app/models/tokenizer.json" \
    -e "OTEL_SDK_DISABLED=true" \
    "$EMBEDDING_JAVA_IMAGE" \
    java -Dfile.encoding=UTF-8 -Xms512m -Xmx1536m \
      -jar /app/app.jar --server.port=8091 >/dev/null

  echo "started smart-embedding-service"
}

start_service() {
  local service="$1"
  local port="$2"
  local module="smart-assistant-${service}"
  local jar="${APP_DIR}/${module}/target/${module}-1.0.0-SNAPSHOT.jar"
  local memory="768m"
  local java_xmx="512m"
  local extra_args=()
  local extra_docker_args=()

  test -s "$jar"

  case "$service" in
    gateway)
      memory="512m"
      java_xmx="384m"
      extra_args+=(
        "--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
      )
      ;;
    consumer)
      local router_ip
      router_ip="$(container_ip smart-router)"
      test -n "$router_ip"
      extra_docker_args+=(
        "-e" "ROUTER_SERVICE_URL=http://${router_ip}:8083"
        "-e" "CONSUMER_LIGHT_MODEL_NAME=qwen2.5:7b"
      )
      extra_args+=(
        "--spring.autoconfigure.exclude=org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration"
        "--spring.aop.auto=false"
        "--spring.ai.mcp.server.enabled=false"
        "--spring.ai.mcp.client.enabled=false"
      )
      ;;
    router)
      # Router creates substantial native/thread state during concurrent routing.
      # Keep the 512 MiB heap but leave enough cgroup headroom to avoid native OOM.
      memory="1280m"
      extra_docker_args+=(
        "-e" "ROUTER_LIGHT_MODEL_NAME=qwen2.5:7b"
        "-e" "ROUTER_QUALITY_EVALUATION_ENABLED=false"
      )
      extra_args+=(
        "--spring.aop.auto=false"
        "--spring.ai.mcp.server.enabled=false"
        "--spring.ai.mcp.client.enabled=false"
      )
      ;;
    product|order)
      memory="1024m"
      java_xmx="768m"
      extra_args+=(
        "--spring.autoconfigure.exclude=org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration"
        "--spring.ai.mcp.server.enabled=false"
        "--spring.ai.mcp.client.enabled=false"
      )
      if [[ "$service" == "product" ]]; then
        extra_docker_args+=(
          "-e" "SPRING_FLYWAY_BASELINE_ON_MIGRATE=true"
        )
      elif [[ "$service" == "order" ]]; then
        extra_docker_args+=(
          "-e" "ORDER_LIGHT_MODEL_NAME=qwen2.5:7b"
        )
      fi
      ;;
    general)
      extra_args+=(
        "--spring.ai.mcp.server.enabled=false"
        "--spring.ai.mcp.client.enabled=false"
      )
      ;;
    user)
      extra_args+=(
        "--spring.autoconfigure.exclude=org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration,org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration"
      )
      ;;
  esac

  replace_container "smart-${service}"
  docker run -d \
    --name "smart-${service}" \
    --network "$NETWORK" \
    --restart unless-stopped \
    --memory "$memory" \
    -v "${jar}:/app/app.jar:ro" \
    -e "PORT=${port}" \
    -e "JWT_SECRET=${JWT_SECRET}" \
    -e "DEEPSEEK_API_KEY=${EFFECTIVE_DEEPSEEK_API_KEY}" \
    -e "SPRING_AI_DEEPSEEK_API_KEY=${EFFECTIVE_DEEPSEEK_API_KEY}" \
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
    -e "SPRING_AI_OLLAMA_BASE_URL=http://${OLLAMA_IP}:11434" \
    -e "MANAGEMENT_ZIPKIN_TRACING_ENDPOINT=http://${ZIPKIN_IP}:9411/api/v2/spans" \
    -e "MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0" \
    -e "EMBEDDING_SERVICE_URL=http://${EMBEDDING_IP}:8091" \
    "${LOCAL_CHAT_ARGS[@]}" \
    "${extra_docker_args[@]}" \
    "$JAVA_IMAGE" \
    java -Dfile.encoding=UTF-8 -Xms256m -Xmx"$java_xmx" \
      -jar /app/app.jar "--server.port=${port}" "${extra_args[@]}" >/dev/null

  echo "started smart-${service}"
}

restart_nginx() {
  local gateway_ip consumer_ip
  gateway_ip="$(container_ip smart-gateway)"
  consumer_ip="$(container_ip smart-consumer)"
  test -n "$gateway_ip"
  test -n "$consumer_ip"

  replace_container smart-nginx
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

  echo "started smart-nginx"
}

if [[ "${1:-full}" == "resume-hotfix" ]]; then
  set_embedding_ip_and_wait
  start_service product 8084
  start_service general 8087
  restart_nginx
  echo "HOTFIX_ROLLOUT_RESUMED"
  exit 0
fi

if [[ "${1:-full}" == "repair-services" ]]; then
  set_embedding_ip_and_wait
  start_service consumer 8082
  sleep 5
  start_service order 8085
  sleep 5
  start_service product 8084
  sleep 5
  restart_nginx
  echo "SERVICES_REPAIRED"
  exit 0
fi

if [[ "${1:-full}" == "product-only" ]]; then
  set_embedding_ip_and_wait
  start_service product 8084
  echo "PRODUCT_REPAIRED"
  exit 0
fi

if [[ "${1:-full}" == "consumer-only" ]]; then
  set_embedding_ip_and_wait
  start_service consumer 8082
  sleep 5
  restart_nginx
  echo "CONSUMER_REPAIRED"
  exit 0
fi

if [[ "${1:-full}" == "router-only" ]]; then
  set_embedding_ip_and_wait
  start_service router 8083
  echo "ROUTER_REPAIRED"
  exit 0
fi

if [[ "${1:-full}" == "order-only" ]]; then
  set_embedding_ip_and_wait
  start_service order 8085
  echo "ORDER_REPAIRED"
  exit 0
fi

if [[ "${1:-full}" == "router-consumer" ]]; then
  set_embedding_ip_and_wait
  start_service router 8083
  sleep 5
  start_service consumer 8082
  sleep 5
  restart_nginx
  echo "ROUTER_CONSUMER_REPAIRED"
  exit 0
fi

if [[ "${1:-full}" == "order-router" ]]; then
  set_embedding_ip_and_wait
  start_service order 8085
  sleep 5
  start_service router 8083
  echo "ORDER_ROUTER_REPAIRED"
  exit 0
fi

if [[ "${1:-full}" == "observability-fix" ]]; then
  set_embedding_ip_and_wait
  start_service gateway 8081
  sleep 5
  start_service order 8085
  sleep 5
  start_service router 8083
  sleep 5
  start_service consumer 8082
  sleep 5
  restart_nginx
  echo "OBSERVABILITY_FIX_ROLLED_OUT"
  exit 0
fi

start_embedding
set_embedding_ip_and_wait

if [[ "${1:-full}" == "hotfix" ]]; then
  start_service router 8083
  start_service consumer 8082
  start_service order 8085
  start_service product 8084
  start_service general 8087
  restart_nginx
  echo "HOTFIX_ROLLOUT_STARTED"
  exit 0
fi

start_service gateway 8081
start_service user 8086
start_service router 8083
start_service consumer 8082
start_service order 8085
start_service product 8084
start_service general 8087
restart_nginx

echo "ROLLOUT_STARTED"
