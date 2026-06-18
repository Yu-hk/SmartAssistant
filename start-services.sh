#!/bin/bash
# 使用 spring-boot:run 直接启动服务（无需等待打包完成）

export MAVEN_HOME="C:/Users/14928/.m2/wrapper/dists/apache-maven-3.9.6/a53741d1"
export MVN_CMD="java -classpath $MAVEN_HOME/boot/plexus-classworlds-2.7.0.jar -Dclassworlds.conf=$MAVEN_HOME/bin/m2.conf -Dmaven.home=$MAVEN_HOME -Dmaven.multiModuleProjectDirectory=D:/workspace/SmartAssistant org.codehaus.plexus.classworlds.launcher.Launcher"

export PROJECT_DIR="D:/workspace/SmartAssistant"

# 加载 .env 环境变量
if [ -f "$PROJECT_DIR/.env" ]; then
  echo "加载 .env 环境变量..."
  # 读取 .env 文件并设置环境变量
  while IFS='=' read -r key value || [ -n "$key" ]; do
    # 跳过注释和空行
    [[ $key =~ ^#.*$ || -z "$key" ]] && continue
    # 移除值中的注释（# 后面的内容）
    value=$(echo "$value" | sed 's/#.*//' | xargs)
    export "$key=$value"
  done < "$PROJECT_DIR/.env"
  echo "✅ 环境变量已加载"
else
  echo "⚠️ .env 文件不存在，使用默认值"
fi

# 清除干扰性环境变量
unset SERVER__PORT

# 服务列表（按启动顺序和依赖关系）
declare -a SERVICES=(
  "smart-assistant-user:8086"
  "smart-assistant-gateway:8081"
  "smart-assistant-embedding-service:8090"
  "smart-assistant-router:8083"
  "smart-assistant-consumer:8082"
  "smart-assistant-order:8085"
  "smart-assistant-product:8084"
  "smart-assistant-general:8087"
)

wait_for_service() {
  local port=$1
  local name=$2
  echo "  ⏳ 等待 $name (端口 $port) 启动..."
  for i in $(seq 1 60); do
    if curl -s "http://localhost:$port/actuator/health" > /dev/null 2>&1; then
      echo "  ✅ $name 已启动 (${i}s)"
      return 0
    fi
    sleep 5
  done
  echo "  ❌ $name 启动超时"
  return 1
}

start_service() {
  local service=$1
  local port=$2
  echo "[$(date '+%H:%M:%S')] 启动 $service (端口 $port)..."
  
  cd "$PROJECT_DIR/$service"
  nohup $MVN_CMD spring-boot:run -DskipTests > "$PROJECT_DIR/logs/$service.log" 2>&1 &
  local pid=$!
  echo "  PID: $pid, 日志: logs/$service.log"
  
  wait_for_service "$port" "$service"
}

echo "=========================================="
echo "  SmartAssistant 服务启动"
echo "=========================================="
echo ""

# 清理旧日志
mkdir -p "$PROJECT_DIR/logs"
> "$PROJECT_DIR/logs/startup.log"

# 启动所有服务
for svc in "${SERVICES[@]}"; do
  IFS=':' read -r name port <<< "$svc"
  start_service "$name" "$port"
  echo "" | tee -a "$PROJECT_DIR/logs/startup.log"
done

echo "=========================================="
echo "  所有服务启动完成"
echo "=========================================="
echo ""
echo "查看日志："
echo "  tail -f logs/<service-name>.log"
echo ""
echo "停止服务："
echo "  ps | grep 'spring-boot:run' | awk '{print \$1}' | xargs kill"
