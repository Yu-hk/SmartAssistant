# SmartAssistant 公网部署建议

## 结论

本项目不是纯静态站点，包含 Java 微服务、PostgreSQL、Redis、Nacos、
向量模型和可选的 Ollama，因此建议部署到一台 Linux 云主机，使用 Docker
Compose 运行。公网只开放 Nginx 的 80/443 端口，其余服务仅在 Docker
内部网络通信。

建议首期配置：

- Ubuntu 22.04/24.04 LTS
- 4 核 CPU、16 GB 内存、120 GB SSD
- 如使用云端 DeepSeek/DashScope API，可不启用 Ollama，降低到 8 GB 内存
- 如必须运行 `deepseek-r1:7b`，建议 16 GB 内存；高并发场景使用 GPU 主机

## 上线前必做

1. 准备域名并将 A/AAAA 记录指向云主机。
2. 云安全组只放行 TCP 22（限制为管理 IP）、80、443。
3. 复制 `deploy/.env.production` 为 `deploy/.env`，替换所有
   `CHANGE_ME_*`，不要提交真实密钥。
4. 在项目根目录构建后端，在 `frontend/` 构建前端。
5. 在 `deploy/` 目录执行：

   ```bash
   docker compose --env-file .env config
   docker compose --env-file .env up -d --build
   docker compose ps
   curl -fsS http://127.0.0.1/healthz
   ```

6. 为域名配置有效 TLS 证书，并将 HTTP 永久重定向到 HTTPS。证书续期必须
   自动化；不要长期以明文 HTTP 对外提供登录、聊天或管理功能。

## 公网边界

只允许以下入口：

- `/`：前端单页应用
- `/api/*`：通过 Nginx 转发到 Gateway
- `/healthz`：最小健康检查

不要在云安全组或 Docker 中发布 PostgreSQL、Redis、Nacos、Ollama、
Zipkin 和各微服务端口。Nacos 官方也明确建议将其部署在隔离的内部网络。

## 推荐上线顺序

1. 先使用云端大模型 API，暂不启动 Ollama，验证完整业务链路。
2. 创建独立的生产数据库备份策略和磁盘快照。
3. 接入 HTTPS 后再开放公网访问。
4. 增加 Nginx/API 限流、登录失败保护、日志脱敏和告警。
5. 压测后再决定是否拆分数据库、模型推理和应用服务。

## 验收清单

- `https://<域名>/` 能打开前端并刷新任意路由
- `https://<域名>/healthz` 返回成功
- 登录、会话创建和 SSE 流式回复可用
- 外网无法访问 5432、6379、8080-8099、8848、9848、9411、11434
- 重启主机后容器自动恢复
- 密钥未出现在仓库、前端产物或日志中
- 数据库备份已完成一次恢复演练
