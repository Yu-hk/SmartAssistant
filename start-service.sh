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
# 从 .env 加载环境变量（通过 DotenvEnvironmentPostProcessor，但 spring-boot:run 需要显式设置）
export REDIS_PASSWORD=redis123
export POSTGRES_PASSWORD=postgres123
export NACOS_PASSWORD=nacos
export NACOS_USERNAME=nacos
export REDIS_HOST=localhost
export REDIS_PORT=6379
export SPRING_AI_DEEPSEEK_API_KEY=sk-dummy-for-compat
export DEEPSEEK_API_KEY=sk-06a0e6f007614f8f90c4f2cc57401b46

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
