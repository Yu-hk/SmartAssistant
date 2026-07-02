# SmartAssistant 生产部署文档

## 推荐云服务器配置

SmartAssistant 包含 8 个微服务 + Ollama 本地 LLM + PostgreSQL + Redis + Nacos，最低推荐配置：

| 配置等级 | 规格 | 适用场景 | 参考价格（月） |
|----------|------|----------|----------------|
| **入门** | 4 核 8G RAM / 80G SSD | 低流量测试/演示，CPU 推理较慢 | ¥80-120 |
| **推荐** | 4 核 16G RAM / 120G SSD | 正常使用，5-10 并发用户 | ¥150-250 |
| **生产** | 8 核 16G RAM / 200G SSD + GPU (T4/A10) | 高并发，GPU 加速推理 | ¥500+ |

> **磁盘注意**: Ollama deepseek-r1:7b 模型约 4.7GB，BGE ONNX 模型约 1.2GB，请确保有足够空间。

### 推荐云厂商

| 厂商 | 产品 | 链接 |
|------|------|------|
| 腾讯云 | 轻量应用服务器 | [https://buy.cloud.tencent.com/lighthouse](https://buy.cloud.tencent.com/lighthouse) |
| 阿里云 | ECS / 轻量应用服务器 | [https://ecs-buy.aliyun.com](https://ecs-buy.aliyun.com) |
| 华为云 | HECS | [https://www.huaweicloud.com/product/hecs.html](https://www.huaweicloud.com/product/hecs.html) |

---

## 部署架构

```
用户 → 域名:80/443 → Nginx (smart-nginx)
                        ├── /api/* → Gateway (8081)
                        ├── /healthz → Gateway /actuator/health
                        └── /* → 前端静态文件 (frontend/dist/)

Gateway → Nacos 服务发现 → User / Consumer / Router / Order / Product / General / Embedding

基础设施：PostgreSQL / Redis / Ollama / Zipkin
```

---

## 部署步骤

### 1. 准备云服务器

购买 Linux 服务器（推荐 Ubuntu 22.04 / CentOS 7+），安装 Docker：

```bash
# Ubuntu
curl -fsSL https://get.docker.com | bash -s docker
sudo usermod -aG docker $USER
newgrp docker

# 安装 Docker Compose v2
sudo apt-get install docker-compose-plugin
```

### 2. 本地构建产物

在开发机（Windows PowerShell）执行：

```powershell
# 方式一：一键构建
.\deploy\build.ps1

# 方式二：分步构建
# 后端（需先设置 JAVA_HOME 为 JDK 21）
set JAVA_HOME=D:\Program Files\Java\jdk-21.0.6+7
.\mvnw.cmd clean package -DskipTests

# 前端
cd frontend
npm run build
cd ..
```

### 3. 上传到服务器

通过 SCP/rsync 将项目上传到服务器：

```bash
# 本地开发机执行
scp -r /path/to/SmartAssistant user@your-server-ip:/home/user/
```

建议使用 rsync（跳过 node_modules 和 .git）：

```bash
rsync -avz --exclude 'frontend/node_modules' --exclude '.git' \
  --exclude 'target' --exclude 'logs' \
  /path/to/SmartAssistant/ user@your-server-ip:/home/user/SmartAssistant/
```

### 4. 配置环境变量

```bash
cd /home/user/SmartAssistant/deploy
cp .env.production .env
# 编辑 .env，填入真实 API Key
vim .env
```

**关键变量说明：**

| 变量 | 说明 | 必须 |
|------|------|------|
| `DEEPSEEK_API_KEY` | DeepSeek API 密钥（从 platform.deepseek.com 获取） | ✅ |
| `POSTGRES_PASSWORD` | PostgreSQL 密码 | ✅ |
| `REDIS_PASSWORD` | Redis 密码 | ✅ |
| `NACOS_PASSWORD` | Nacos 密码 | ✅ |
| `JWT_SECRET` | JWT 签名密钥（建议 256 位随机字符串） | ✅ |

### 5. 启动服务

```bash
cd /home/user/SmartAssistant/deploy

# 首次启动（会自动拉取 Ollama 模型，耗时较长）
docker compose --env-file .env up -d

# 查看启动进度
docker compose logs -f

# 检查各服务健康状态
docker compose ps
```

### 6. 验证部署

```bash
# 健康检查
curl http://localhost:80/healthz

# API 测试
curl http://localhost:80/api/auth/health

# 访问前端
curl http://localhost:80/
```

### 7. 配置域名 + HTTPS（可选）

```bash
# 使用 certbot 获取 SSL 证书
sudo apt-get install certbot python3-certbot-nginx
sudo certbot --nginx -d your-domain.com
```

配置完成后，取消 `deploy/nginx/default.conf` 中 HTTPS 部分的注释。

---

## 服务端口映射

| 服务 | 容器内端口 | 是否对外暴露 | 说明 |
|------|-----------|-------------|------|
| Nginx | 80/443 | ✅ 80/443 | 唯一对外入口 |
| Gateway | 8081 | ❌ | API 网关 |
| Consumer | 8082 | ❌ | SSE 聊天入口 |
| Router | 8083 | ❌ | 智能路由 |
| Product | 8084 | ❌ | 商品智能体 |
| Order | 8085 | ❌ | 订单智能体 |
| User | 8086 | ❌ | 用户认证 |
| General | 8087 | ❌ | 通用对话 |
| Embedding | 8091 | ❌ | 向量嵌入服务 |
| Nacos | 8848 | ❌ | 服务注册 |
| Redis | 6379 | ❌ | 缓存 |
| PostgreSQL | 5432 | ❌ | 数据库 |
| Ollama | 11434 | ❌ | 本地 LLM |
| Zipkin | 9411 | ❌ | 链路追踪 |

---

## 运维命令

```bash
# 查看所有服务状态
docker compose ps

# 查看日志（实时）
docker compose logs -f

# 查看单个服务日志
docker compose logs -f smart-gateway

# 重启单个服务
docker compose restart smart-router

# 滚动重启所有服务
docker compose restart

# 停止所有服务
docker compose down

# 停止并删除数据卷（⚠️ 会清空数据库和数据）
docker compose down -v

# 重新构建某个服务后重启
docker compose build smart-router
docker compose up -d smart-router
```

---

## 资源占用预估

| 服务 | RAM 限制 | 实际占用 |
|------|----------|----------|
| Nginx | - | ~10MB |
| Redis | - | ~50MB |
| Nacos | 512M | ~350MB |
| PostgreSQL | - | ~200MB |
| Ollama | - | **4-8GB** (7B 模型) |
| 8 x 后端服务 | 各 512M | ~2GB 总计 |
| **总计** | | **~7-10GB** |

---

## 故障排查

### 服务启动后立即退出
- 检查端口冲突：`sudo lsof -i :8081`
- 检查 Nacos 是否就绪：`curl http://localhost:8848/nacos/`
- 检查日志：`docker compose logs -f smart-gateway`
- 查看容器状态：`docker compose ps -a`

### Nacos 注册失败
- 确认 Nacos 健康：`curl http://smart-nacos:8848/nacos/`
- 确认密码正确：检查 `.env` 中的 `NACOS_PASSWORD`
- 检查网络：`docker exec smart-gateway ping smart-nacos`

### Ollama 模型拉取失败
- 检查磁盘空间：`df -h`
- 手动拉取：`docker exec smart-ollama ollama pull deepseek-r1:7b`
- 查看日志：`docker compose logs smart-ollama-setup`

### 前端页面空白
- 确认 `frontend/dist/` 存在且有内容
- 确认 Nginx 配置正确：`docker compose exec smart-nginx nginx -t`
- 检查 Nginx 日志：`docker compose logs smart-nginx`
- 浏览器打开 F12 查看网络请求错误
