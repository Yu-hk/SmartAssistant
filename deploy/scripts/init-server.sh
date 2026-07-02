#!/bin/bash
# =============================================================================
# SmartAssistant - 服务器初始化脚本
# 在阿里云轻量服务器上执行（root 权限）
# 用途：安装 Docker、Docker Compose、配置防火墙、基础优化
# =============================================================================
set -e

echo "============================================"
echo " SmartAssistant - 服务器初始化"
echo "============================================"

# 1. 更新系统
# 阿里云 Alibaba Cloud Linux 3 基于 CentOS 7
yum install -y yum-utils device-mapper-persistent-data lvm2 curl

# 2. 安装 Docker
if ! command -v docker &> /dev/null; then
    echo "[1/5] 安装 Docker..."
    curl -fsSL https://get.docker.com | bash -s docker
    systemctl enable docker
    systemctl start docker
    docker --version
    echo "  Docker 安装完成"
else
    echo "  Docker 已安装：$(docker --version)"
fi

# 3. 安装 Docker Compose v2
if ! docker compose version &> /dev/null; then
    echo "[2/5] 安装 Docker Compose..."
    # 新脚本通过 get.docker.com 应该已经安装了 Compose 插件
    # 如果没装，手动安装
    COMPOSE_VERSION="v2.27.0"
    DOCKER_CONFIG="${DOCKER_CONFIG:-$HOME/.docker}"
    mkdir -p "$DOCKER_CONFIG/cli-plugins"
    curl -SL "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-x86_64" -o "$DOCKER_CONFIG/cli-plugins/docker-compose"
    chmod +x "$DOCKER_CONFIG/cli-plugins/docker-compose"
    docker compose version
    echo "  Docker Compose 安装完成"
else
    echo "  Docker Compose 已安装：$(docker compose version)"
fi

# 4. 配置 Docker 国内镜像加速（阿里云）
if [ ! -f /etc/docker/daemon.json ]; then
    echo "[3/5] 配置 Docker 镜像加速..."
    mkdir -p /etc/docker
    cat > /etc/docker/daemon.json << 'EOF'
{
  "registry-mirrors": [
    "https://mirror.ccs.tencentyun.com",
    "https://docker.mirrors.ustc.edu.cn",
    "https://hub-mirror.c.163.com"
  ],
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  }
}
EOF
    systemctl restart docker
    echo "  Docker 镜像加速配置完成"
else
    echo "  Docker 配置已存在，跳过"
fi

# 5. 防火墙配置（阿里云轻量服务器使用 firewalld，但实际上 Docker 容器间网络默认隔离）
echo "[4/5] 检查防火墙..."
# 轻量服务器控制台防火墙需要手动配置，这里只做系统层面检查
if command -v firewall-cmd &> /dev/null; then
    firewall-cmd --permanent --add-port=80/tcp
    firewall-cmd --permanent --add-port=443/tcp
    firewall-cmd --permanent --add-port=8080-8099/tcp
    firewall-cmd --permanent --add-port=8848/tcp
    firewall-cmd --reload
    echo "  Firewalld 端口已放行"
else
    echo "  firewalld 未安装，请在阿里云控制台配置防火墙"
fi

# 6. 创建应用目录
echo "[5/5] 创建应用目录..."
mkdir -p /opt/smart-assistant
chown -R $(whoami):$(whoami) /opt/smart-assistant

echo "============================================"
echo " 服务器初始化完成！"
echo "============================================"
echo ""
echo "请在阿里云控制台 → 防火墙 → 添加规则："
echo "  TCP 80-443, 8080-8099, 8848, 11434"
echo ""
echo "接下来将项目文件上传到 /opt/smart-assistant 即可启动"
echo "============================================"
