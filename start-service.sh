#!/bin/bash
# SmartAssistant 服务启动器 - 使用 spring-boot:run
# 用法: ./start-service.sh <module-name>

MODULE=$1
if [ -z "$MODULE" ]; then
  echo "Usage: $0 <module-name>"
  echo "Available: smart-assistant-user smart-assistant-gateway smart-assistant-embedding-service smart-assistant-router smart-assistant-consumer smart-assistant-order smart-assistant-product smart-assistant-general"
  exit 1
fi

PROJECT_DIR="D:/workspace/SmartAssistant"
MVN_HOME="C:/Users/14928/.m2/wrapper/dists/apache-maven-3.9.6/a53741d1"

# 清除 SERVER__PORT 陷阱
unset SERVER__PORT
# 从本地 .env 加载密钥，禁止把真实 API Key 写入启动脚本。
if [ -f "$PROJECT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env"
  set +a
fi

case "$MODULE" in
  smart-assistant-consumer|smart-assistant-router|smart-assistant-order|smart-assistant-product|smart-assistant-general)
    : "${DEEPSEEK_API_KEY:?DEEPSEEK_API_KEY must be set in .env}"
    ;;
esac

cd "$PROJECT_DIR/$MODULE"
echo "Starting $MODULE on $(date)..." >> "$PROJECT_DIR/logs/startup.log"

nohup java \
  -classpath "$MVN_HOME/boot/plexus-classworlds-2.7.0.jar" \
  -Dclassworlds.conf="$MVN_HOME/bin/m2.conf" \
  -Dmaven.home="$MVN_HOME" \
  -Dmaven.multiModuleProjectDirectory="$PROJECT_DIR" \
  org.codehaus.plexus.classworlds.launcher.Launcher \
  spring-boot:run -DskipTests \
  > "$PROJECT_DIR/logs/$MODULE.log" 2>&1 &

PID=$!
echo "$MODULE started with PID=$PID"
echo "$MODULE: PID=$PID started at $(date)" >> "$PROJECT_DIR/logs/startup.log"
