# SmartAssistant

SmartAssistant 是一个基于 Spring Boot、Spring AI 和 React 的多智能体对话系统。系统通过 Gateway 统一接入请求，由 Consumer 管理对话、情绪预处理、用户画像和语义答案缓存，并通过 RabbitMQ 调度业务请求。Router 负责意图识别、任务分发和 Agent 协调，调用订单、商品等领域服务；分配失败时由 Router 内置 Agent 配合 Tool Registry / Runtime 完成兜底。

## 主要能力

用户通过登录页访问工作台，普通用户与管理员使用不同页面，并遵守各自的权限与数据隔离约束。此前的演示账号入口已移除。

- 用户登录、权限控制与会话隔离
- 多轮对话、历史会话管理和人工关闭会话
- 聊天框语音输入：Qwen ASR 转文字，核对后手动发送（需开启服务端配置，见 [接入说明](docs/voice-input.md)）
- 配套语音回复：语音提问完成后自动播报，文字回复可手动朗读，支持暂停/继续/停止（见 [语音回复说明](docs/voice-output.md)）
- 多 Agent 路由与任务编排
- 订单、商品与推荐能力，以及基于 Tool Registry / Runtime 的通用兜底
- RAG 文档解析、向量检索、重排序与评测门禁
- Tool Registry 与 MCP 兼容的工具发现
- Prometheus、Grafana、Loki 和 Jaeger 可观测性配置

## 运行时架构

<p align="center">
  <img src="docs/architecture/smartassistant-runtime.svg" alt="SmartAssistant 高层运行时架构" width="100%">
</p>

启用 MQ 时的主请求路径是 `React → Gateway → Consumer 接入 → RabbitMQ → Consumer 执行器 → Router → 业务 Agent`。图中的 Consumer 接入和执行器属于同一服务，Product / Order 则是两个独立领域服务；模型与检索节点是逻辑依赖组，不是新增的统一微服务。

1. 前端统一通过 Gateway 访问认证、对话和运营接口。
2. Consumer 在独立有界执行器中并行情绪分析与画像准备。情绪推理默认预算 750 ms，画像异步更新不阻塞请求线程。
3. 服务端根据本轮情绪建议确定 MQ 优先级。RabbitMQ 4.1 Quorum 队列区分普通 0 / 高 5；每个 Consumer 实例默认 4 路消费、预取 1、手工 ACK，不抢占已运行任务。Redis 记录执行权与结果，不确定业务进入死信核查，不自动重做订单操作。
4. Router 只承担规划、协调与内置兜底。商品节点在当前 Router 实例内对同一轮画像最多额外等待 500 ms，超时或读取失败无画像继续；后续商品节点复用选定结果。
5. Product 从共享商品目录读取结构化参数，执行候选筛选、证据核实与推荐理由生成；金额和预算状态由程序校验，默认结论不含差额。Order 在补齐参数、用户二次确认后进入确定性工作流。画像和情绪不能替代业务证据或写操作确认。
6. PostgreSQL/pgvector 保存业务、画像版本与向量数据；Redis 保存短期上下文、缓存、执行权和检查点。RabbitMQ 还承担画像提交与工作流恢复等独立队列。
7. Nacos 提供服务注册发现，监控配置覆盖 Prometheus、Grafana、Loki 与链路追踪。高层图省略共享依赖的其他访问边与监控连线，完整配置见 `deploy/docker-compose.yml`。

管理员商品录入是独立管理 API 路径：简介/规格规则提取 → 预览与人工核对 → Consumer 同事务保存商品、参数及审计。该流程不经聊天 MQ/Router，也不调用大模型；Product 推荐时读取已存事实，未知参数不猜测。详见 [商品录入与推荐读取架构](docs/architecture/product-intake.md) 和 [线上部署验收](docs/product-intake-deployment-verification.md)。

语义答案缓存只覆盖短时效商品咨询和文档绑定的业务咨询，其他场景不进入缓存；完整边界见 [语义答案缓存策略](docs/semantic-cache-policy.md)。

设计与边界：[情绪并行预处理](docs/architecture/sentiment-preprocessing.md) · [MQ 优先级调度](docs/architecture/chat-priority-mq.md) · [可选画像与等待上限](docs/architecture/optional-user-profile.md)。

## 项目结构

| 路径 | 说明 |
| --- | --- |
| `smart-assistant-gateway/` | API 网关，默认端口 8081 |
| `smart-assistant-router/` | 意图识别、任务分发、Agent 协调与最终兜底 |
| `smart-assistant-consumer/` | 对话、情绪预处理、用户画像、MQ 调度、商品录入与参数持久化、反馈与运营接口 |
| `smart-assistant-user/` | 用户、认证与权限 |
| `smart-assistant-order/` | 订单查询与订单工具 |
| `smart-assistant-product/` | 商品检索、商品知识库与推荐 |
| `smart-assistant-tool-runtime/` | 可嵌入的通用工具实现，不包含服务端传输 |
| `smart-assistant-tool-registry/` | 工具注册、发现、MCP 与生命周期管理 |
| `smart-assistant-routing-contract/` | Router/Consumer 共享的路由通信契约 |
| `smart-assistant-embedding-service/` | Embedding 服务 |
| `smart-assistant-common/` | 公共模型、RAG、评测与基础组件 |
| `frontend/` | React/Vite 前端；开发环境通过 Vite 代理访问 Gateway |
| `docs/` | 架构、设计、运维和评测文档 |
| `deploy/` | 生产部署配置 |
| `monitoring/` | 可观测性配置 |

## 环境要求

- JDK 21
- Docker 与 Docker Compose
- Node.js 20 或更高版本
- Git

项目已包含 Maven Wrapper，不需要额外安装 Maven。

## 本地开发

1. 创建本地环境变量文件：

   ```powershell
   Copy-Item .env.example .env
   ```

   Linux/macOS 可使用：

   ```bash
   cp .env.example .env
   ```

2. 按照 `.env.example` 填写数据库、Redis、JWT 和模型服务配置。不要提交真实密钥。

3. 启动本地基础设施：

   ```bash
   docker compose -f docker-compose.dev.yml up -d
   ```

4. 编译并运行后端测试：

   Windows：

   ```powershell
   .\mvnw.cmd test
   ```

   Linux/macOS：

   ```bash
   ./mvnw test
   ```

5. 启动前端：

   ```bash
   cd frontend
   npm ci
   npm run dev
   ```

前端开发服务器的代理目标在 `frontend/vite.config.ts` 中配置。

## 部署

生产部署的唯一入口是 `deploy/docker-compose.yml`。部署前必须通过环境变量注入真实密钥，禁止把 `.env`、数据库转储、运行日志或用户会话数据提交到仓库。

参考：

- [生产部署说明](deploy/README.md)
- [Docker 镜像清单](docs/DOCKER.md)
- [生产就绪检查清单](docs/production-readiness-checklist.md)

## 测试与质量门禁

GitHub Actions 会执行：

- 全模块编译
- Maven Enforcer 与 JaCoCo 质量检查
- Router E2E 测试
- 黄金评测集门禁
- Tool Manifest 校验
- 依赖漏洞与密钥泄漏扫描

评测数据保存在 `docs/eval/` 和模块测试资源中。一次性联调数据、生成报告及运行时用户数据不进入版本控制。

## 文档

- [交互式运行时架构图](docs/architecture/smartassistant-runtime.architecture.html)
- [运行时架构规范](docs/architecture/smartassistant-runtime.architecture.json)
- [架构图生成与验证记录](docs/architecture/runtime-diagram-verification.md)
- [商品录入与推荐读取架构](docs/architecture/product-intake.md)
- [系统设计](docs/system_design.md)
- [架构演进路线](docs/architecture-roadmap.md)
- [RAG 生产化设计](docs/rag-production/ARCHITECTURE.md)
- [RAG 实现记录](docs/rag-production/IMPLEMENTATION.md)
- [Tool Registry 方案](docs/tool-registry-plan.md)
- [前端说明](frontend/README.md)

## 安全约定

- 所有密钥仅通过环境变量或未跟踪的本地 `.env` 提供。
- `data/users/` 和 `data/corrections/` 属于运行时数据，不得提交。
- 本地生成的模型权重、构建产物、日志和数据库文件不得提交。
- 如历史提交中出现过真实凭据，应立即轮换；删除当前文件不会清除 Git 历史。
