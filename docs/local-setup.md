# 本地环境搭建

本文档说明在开发机搭建 **SmartAssistant** 的完整本地环境。平台由 10 个可独立部署的微服务 + 1 个共享库（`smart-assistant-common`）+ 前端组成。

## 1. 组件清单

| 类别 | 组件 | 版本/说明 |
|------|------|-----------|
| 构建 | JDK | **21**（Temurin 21.0.6+） |
| 构建 | Maven | **3.9+** |
| 前端 | Node | **22+** |
| 中间件 | Nacos | 服务发现 + 配置中心（默认 `localhost:8848`） |
| 中间件 | Redis | 会话/缓存（默认 `localhost:6379`） |
| 中间件 | PostgreSQL | 业务库（库 `a2a_system`，默认 `5432`） |
| 模型 | Ollama | 本地 LLM（`deepseek-r1:7b` 等） |
| 模型 | 嵌入服务 | 本地 BGE ONNX（`smart-assistant-embedding-service`，8091） |
| 可观测 | Prometheus + Alertmanager + Grafana | 指标 / 告警 / 看板（见 `monitoring/`） |

> 密钥（如 `DEEPSEEK_API_KEY` / `DASHSCOPE_API_KEY` / `AMAP_API_KEY`）通过环境变量或 `.env` 注入，请勿提交明文。

## 2. 一键编排（推荐）

仓库根目录提供多套 `docker-compose*.yml`。本地联调以基础编排为准（Postgres / Redis / Nacos / 各微服务 / 前端 / 监控）：

```bash
cp .env.example .env                     # 按需填写密钥（根目录 .env.example）
docker compose up -d                     # 启动全部依赖与微服务
```

启动后浏览器访问：

- 前端：http://localhost:3001（或 5173，依前端配置）
- 网关：http://localhost:8081
- Grafana：http://localhost:3000

## 3. 仅启动中间件（前后端本地跑）

若想在 IDE 内逐个启动微服务、只把中间件交给 Docker：

```bash
docker compose up -d postgres redis nacos     # 仅依赖中间件
```

随后在 IDE 或以 Maven 启动各模块主类（`*Application`）：

```bash
mvn -pl smart-assistant-gateway -am spring-boot:run
# 其余模块类似：-pl smart-assistant-router / -consumer / -product / -order / -user / -general / -recommend / -tool-registry / -embedding-service
```

## 4. 服务端口表（与代码一致）

| 服务 | 端口 | 说明 |
|------|------|------|
| smart-assistant-gateway | 8081 | API 网关、鉴权 |
| smart-assistant-consumer | 8082 | SSE 流式对话入口（MCP Agent） |
| smart-assistant-router | 8083 | 意图路由、多 Agent 协作 |
| smart-assistant-product | 8084 | 商品查询、图谱、RAG |
| smart-assistant-order | 8085 | 订单查询（Text-to-SQL）、知识库 |
| smart-assistant-user | 8086 | 用户注册登录、JWT |
| smart-assistant-general | 8087 | 闲聊/单位转换/脚本沙箱（仅 A2A） |
| smart-assistant-tool-registry | 8088 | 工具注册中心 + MCP 发现 |
| smart-assistant-recommend | 8089 | P3 跨模块推荐（图谱→协同过滤→热门兜底） |
| smart-assistant-embedding-service | 8091 | BGE 向量嵌入 |
| smart-assistant-common | — | 共享库（非独立部署，控制器由引入它的 Web 服务激活） |

> ⚠️ 端口冲突历史坑：recommend 与 tool-registry 曾同绑 8088，已在 Phase 3 将 recommend 改为 **8089**。若本地同时启动二者，请确保使用修正后的端口。

## 5. 前端

```bash
cd frontend
npm install
npm run dev          # 开发服务器，默认 3001 / 5173
```

## 6. 运行测试

```bash
# 全量
mvn test

# 指定模块 + 依赖
mvn -pl smart-assistant-recommend,smart-assistant-general -am test

# 指定用例（避免触发需外部组件的集成测试）
mvn test -Dtest=RecommendServiceTest,UnitConversionTest -Dsurefire.failIfNoSpecifiedTests=false
```

## 7. 常见问题

- **`NoClassDefFoundError: org/springframework/core/Nullness`**：Spring AI 版本与 Spring Framework 不匹配。当前固定 `spring-ai 1.0.9` + Spring Boot 3.5，请勿误升到 2.x。
- **`SERVER__PORT` 环境变量覆盖 `server.port`**：启动前 `unset SERVER__PORT`，否则端口被环境劫持。
- **Nacos 鉴权**：本地 Nacos 若开启认证，需配置 `NACOS_AUTH_ENABLE` 与相关账号。
