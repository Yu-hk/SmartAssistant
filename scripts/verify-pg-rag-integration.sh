#!/usr/bin/env bash
#
# SmartAssistant RAG 生产化 —— PostgreSQL + pgvector 真集成一键验证脚本（Git Bash）
#
# 补齐「RAG 生产化改造」唯一未连环境验证的环节：
#   1) 检查 Docker 可用
#   2) 启动 docker-compose-infra.yml 中的 postgres 服务
#      （pgvector/pgvector:0.8.0-pg16，端口 5433:5432，库 a2a_system，用户 postgres/postgres123）
#   3) 轮询 pg_isready 直到就绪（超时 60s）
#   4) 运行 PgVectorKnowledgeBaseIntegrationTest（-Dpg.integration=true）
#   5) 输出结果，可选停止 postgres
#
# 用法:
#   ./scripts/verify-pg-rag-integration.sh
#   STOP_POSTGRES=true ./scripts/verify-pg-rag-integration.sh
#
set -uo pipefail

# ---------- 路径解析 ----------
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$PROJECT_ROOT/docker-compose-infra.yml"

# ---------- 工具默认路径（可用环境变量覆盖） ----------
MAVEN_EXE="${MAVEN_EXE:-${MAVEN_HOME:+${MAVEN_HOME}/bin/mvn}}"
MAVEN_EXE="${MAVEN_EXE:-/d/maven/apache-maven-3.9.6/bin/mvn}"
JAVA_HOME="${JAVA_HOME:-/d/Program Files/Java/jdk-21.0.6+7}"
export JAVA_HOME

STOP_POSTGRES="${STOP_POSTGRES:-false}"

echo ""
echo "============================================================"
echo " SmartAssistant PG+RAG 集成验证（Git Bash）"
echo "============================================================"
echo " 项目根目录: $PROJECT_ROOT"
echo " 编排文件:   $COMPOSE_FILE"
echo " Maven:      $MAVEN_EXE"
echo " JAVA_HOME:  $JAVA_HOME"
echo "============================================================"

# ---------- 1) 检查 Docker ----------
echo ""
echo "[1/4] 检查 Docker 可用性..."
if ! command -v docker >/dev/null 2>&1; then
  echo "  错误：未检测到 docker，请先安装并启动 Docker。" >&2
  exit 1
fi
echo "  Docker 可用: $(docker --version)"

# 选择 compose 命令（优先 v2 'docker compose'，回退 v1 'docker-compose'）
if docker compose version >/dev/null 2>&1; then
  COMPOSE="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE="docker-compose"
else
  echo "  错误：未检测到 docker compose / docker-compose 插件。" >&2
  exit 1
fi
echo "  使用编排命令: $COMPOSE"

# ---------- 2) 启动 postgres ----------
echo ""
echo "[2/4] 启动 PostgreSQL + pgvector 服务..."
$COMPOSE -f "$COMPOSE_FILE" up postgres -d
if [ $? -ne 0 ]; then
  echo "  错误：启动 postgres 失败，请检查 Docker 状态。" >&2
  exit 1
fi
echo "  postgres 服务已启动（端口 5433:5432，库 a2a_system，用户 postgres/postgres123）。"

# ---------- 3) 轮询 pg_isready ----------
echo ""
echo "[3/4] 等待 PostgreSQL 就绪（超时 60s）..."
READY=false
for i in $(seq 1 30); do
  if docker exec smart-postgres pg_isready -U postgres -d a2a_system >/dev/null 2>&1; then
    READY=true
    echo "  PostgreSQL 已就绪（第 $i 次探测）。"
    break
  fi
  echo "  等待中... ($i/30)"
  sleep 2
done
if [ "$READY" = false ]; then
  echo "  错误：60s 内 PostgreSQL 未就绪，请检查容器日志。" >&2
  $COMPOSE -f "$COMPOSE_FILE" logs postgres
  exit 1
fi

# ---------- 4) 运行集成测试 ----------
echo ""
echo "[4/4] 运行 PgVectorKnowledgeBaseIntegrationTest..."
"$MAVEN_EXE" -pl smart-assistant-common test \
  -Dtest=PgVectorKnowledgeBaseIntegrationTest -Dpg.integration=true
TEST_EXIT=$?

echo ""
echo "============================================================"
if [ $TEST_EXIT -eq 0 ]; then
  echo " 集成测试执行完成（BUILD SUCCESS）。详见上方 Surefire 报告与 target/surefire-reports。"
else
  echo " 集成测试存在失败（BUILD FAILURE）。请检查上方输出与 target/surefire-reports/PgVectorKnowledgeBaseIntegrationTest.txt。"
fi
echo "============================================================"

# ---------- 可选：停止 postgres ----------
if [ "$STOP_POSTGRES" = true ]; then
  echo ""
  echo "按要求停止 postgres 服务..."
  $COMPOSE -f "$COMPOSE_FILE" stop postgres
fi

exit $TEST_EXIT
