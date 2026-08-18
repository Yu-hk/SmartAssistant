# SmartAssistant 生产部署文档

## 推荐云服务器配置

SmartAssistant 包含 8 个微服务，并使用 DeepSeek API、PostgreSQL、Redis 与 Nacos，最低推荐配置：

| 配置等级 | 规格 | 适用场景 | 参考价格（月） |
|----------|------|----------|----------------|
| **入门** | 2 核 4G RAM / 60G SSD | 低流量测试/演示 | ¥50-100 |
| **推荐** | 4 核 8G RAM / 100G SSD | 正常使用，5-10 并发用户 | ¥100-200 |
| **生产** | 8 核 16G RAM / 200G SSD | 高并发与完整可观测能力 | ¥300+ |

> **磁盘注意**：项目不再存放本地大语言模型；仍需为镜像、日志和向量索引预留空间。

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

Gateway → Nacos 服务发现 → User / Consumer / Router / Tool Registry / Order / Product / Embedding

基础设施：PostgreSQL / Redis / Nacos / Zipkin；LLM 通过 DeepSeek API 调用
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

在项目根目录执行（需要 JDK 21、Node.js 20+）：

```bash
bash deploy/build.sh
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

# 首次启动
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

### 7. 配置域名 + HTTPS

```bash
# 1. 在 DNS 控制台添加记录
#    @   A   123.56.6.102
#    www A   123.56.6.102

# 2. DNS 生效后，用容器化 Certbot 和当前 HTTP 站点申请证书
sudo install -d -m 0755 /opt/smart-assistant/letsencrypt
docker run --rm \
  -v /opt/smart-assistant/frontend/dist:/var/www/certbot \
  -v /opt/smart-assistant/letsencrypt:/etc/letsencrypt \
  docker.io/certbot/certbot:latest certonly --webroot \
  -w /var/www/certbot --register-unsafely-without-email --agree-tos \
  -d xiaoyuai.cloud -d www.xiaoyuai.cloud

# 3. 将证书部署到 Nginx 的只读挂载目录
sudo install -d -m 0755 /opt/smart-assistant/deploy/nginx/ssl
sudo install -m 0644 /opt/smart-assistant/letsencrypt/live/xiaoyuai.cloud/fullchain.pem \
  /opt/smart-assistant/deploy/nginx/ssl/fullchain.pem
sudo install -m 0600 /opt/smart-assistant/letsencrypt/live/xiaoyuai.cloud/privkey.pem \
  /opt/smart-assistant/deploy/nginx/ssl/privkey.pem

# 4. 校验配置并重新创建 Nginx，使 443 端口映射生效
docker compose --env-file .env run --rm --no-deps nginx nginx -t
docker compose --env-file .env up -d --force-recreate nginx

# 5. 安装每日证书续期检查（续期成功后自动校验并热重载 Nginx）
chmod 0755 /opt/smart-assistant/deploy/renew-cert.sh
sudo install -m 0644 deploy/systemd/smart-assistant-cert-renew.service \
  /etc/systemd/system/smart-assistant-cert-renew.service
sudo install -m 0644 deploy/systemd/smart-assistant-cert-renew.timer \
  /etc/systemd/system/smart-assistant-cert-renew.timer
sudo systemctl daemon-reload
sudo systemctl enable --now smart-assistant-cert-renew.timer
```

`deploy/nginx/default.conf` 已固定使用 `xiaoyuai.cloud`，并将 HTTP、`www` 和直接 IP 访问统一重定向到 `https://xiaoyuai.cloud`。`deploy/renew-cert.sh` 负责续期、复制证书并热重载 Nginx。

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
| Tool Registry | 8088 | ❌ | 工具注册与发现 |
| Embedding | 8091 | ❌ | 向量嵌入服务 |
| Nacos | 8848 | ❌ | 服务注册 |
| Redis | 6379 | ❌ | 缓存 |
| PostgreSQL | 5432 | ❌ | 数据库 |
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
| 8 x 后端服务 | 各 512M | ~2GB 总计 |
| **总计** | | **~3-5GB** |

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

### DeepSeek 调用失败
- 确认 `.env` 中 `DEEPSEEK_API_KEY` 已设置且有效
- 检查 Router/Consumer 日志中的 HTTP 状态码和模型名称
- 确认服务器可以访问 DeepSeek API

### 前端页面空白
- 确认 `frontend/dist/` 存在且有内容
- 确认 Nginx 配置正确：`docker compose exec smart-nginx nginx -t`
- 检查 Nginx 日志：`docker compose logs smart-nginx`
- 浏览器打开 F12 查看网络请求错误
