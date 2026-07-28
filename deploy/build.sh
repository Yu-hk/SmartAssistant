#!/bin/bash
# =============================================================================
# SmartAssistant - 一键构建脚本
# 用于在本地或 CI 环境构建所有部署产物
# =============================================================================
set -e

echo "============================================"
echo " SmartAssistant - 构建开始"
echo "============================================"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

# 1. 检查 JDK 21
if ! command -v java &> /dev/null || ! command -v javac &> /dev/null; then
    echo "ERROR: JDK 21 not found. Please install a complete JDK 21."
    exit 1
fi

JAVA_VERSION_LINE="$(java -version 2>&1 | head -1)"
JAVAC_VERSION_LINE="$(javac -version 2>&1 | head -1)"
JAVA_VERSION="$(printf '%s\n' "$JAVA_VERSION_LINE" | sed -nE 's/.*version "([0-9]+).*/\1/p')"
JAVAC_VERSION="$(printf '%s\n' "$JAVAC_VERSION_LINE" | sed -nE 's/^javac ([0-9]+).*/\1/p')"
if [ "$JAVA_VERSION" != "21" ] || [ "$JAVAC_VERSION" != "21" ]; then
    echo "ERROR: JDK 21 is required."
    echo "  java:  $JAVA_VERSION_LINE"
    echo "  javac: $JAVAC_VERSION_LINE"
    exit 1
fi
echo "[1/3] Java version: $JAVA_VERSION_LINE"

# 2. Maven 构建后端
echo "[2/3] Building backend services with Maven..."
if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "cygwin" ]]; then
    # Windows
    ./mvnw.cmd clean package -DskipTests -q
else
    # Linux / macOS
    ./mvnw clean package -DskipTests -q
fi
echo "  Backend build complete."

# 3. 构建前端
echo "[3/3] Building frontend..."
cd frontend

if ! command -v node &> /dev/null || ! command -v npm &> /dev/null; then
    echo "ERROR: Node.js or npm not found."
    exit 1
fi

# 使用 managed node 的 full path
NODE_BIN=$(which node 2>/dev/null || echo "")
if [ -z "$NODE_BIN" ] && [ -f "/usr/local/bin/node" ]; then
    NODE_BIN="/usr/local/bin/node"
fi

if [ ! -d node_modules ]; then
    if [ -f package-lock.json ]; then
        echo "  node_modules not found; running npm ci..."
        npm ci
    else
        echo "  package-lock.json not found; running npm install..."
        npm install --silent
    fi
fi
npm run build
echo "  Frontend build complete."

echo "============================================"
echo " 构建产物："
echo "  - 后端 JAR: */target/*.jar"
echo "  - 前端:     frontend/dist/"
echo "============================================"
echo ""
echo "部署到服务器后，在 deploy/ 目录执行："
echo "  cp .env.production .env"
echo "  docker compose --env-file .env up -d"
echo "============================================"
