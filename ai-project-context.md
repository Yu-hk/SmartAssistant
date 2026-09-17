# SmartAssistant — 当前项目上下文

本文件供开发者和编码助手阅读，以仓库声明为准，不是客户对话的业务 System Prompt。
当前 `PromptManager` 只加载 classpath 下的提示词，没有 `loadProjectContext()` 方法。
历史设计文档中的组件、版本与路线图不代表已启用能力。

## 技术与模块事实（自动核对）

<!-- BEGIN GENERATED FACTS -->
| 技术 | 仓库声明 |
| --- | --- |
| Java | `21` |
| Spring Boot | `4.1.0` |
| Spring AI | `2.0.1` |
| Spring Cloud | `2025.1.2` |
| Spring Cloud Alibaba | `2025.1.0.0` |
| Nacos client | `3.2.0` |
| langgraph4j | `1.8.20` |
| react (声明范围) | `^18.2.0` |
| react-router-dom (声明范围) | `^7.18.2` |
| tdesign-react (声明范围) | `^1.12.0` |
| vite (声明范围) | `^5.0.10` |
| tailwindcss (声明范围) | `^3.4.17` |

Maven 模块共 11 个；以下端口来自各模块 application.yml 默认声明。

| 模块 | 默认端口 / 类型 |
| --- | --- |
| `smart-assistant-common` | 共享库（无独立端口） |
| `smart-assistant-routing-contract` | 共享库（无独立端口） |
| `smart-assistant-tool-runtime` | 共享库（无独立端口） |
| `smart-assistant-embedding-service` | 8091 |
| `smart-assistant-consumer` | 8082 |
| `smart-assistant-router` | 8083 |
| `smart-assistant-order` | 8085 |
| `smart-assistant-product` | 8084 |
| `smart-assistant-gateway` | 8081 |
| `smart-assistant-user` | 8086 |
| `smart-assistant-tool-registry` | 8088 |

基础设施镜像（`docker-compose-infra.yml`，不等于线上实测版本）：
- redis: `redis:7.2.4`
- rabbitmq: `rabbitmq:4.1-management-alpine`
- nacos: `nacos/nacos-server:v3.1.0`
- postgres: `pgvector/pgvector:0.8.0-pg16`
<!-- END GENERATED FACTS -->

修改依赖、服务或基础设施后执行 `python scripts/check_project_context.py --write`；
CI 执行不带 `--write` 的检查，文档与声明不一致即失败。
版本表只证明声明值，不等于运行时兼容性认证或线上容器盘点。

## 当前职责与请求路径

用户请求经 Gateway → Consumer（情绪/画像并行预处理、接入与 MQ 执行器）→ Router → Product / Order / 内置兜底。
Consumer 接入与执行器是同一个服务，不要再拆算为两个部署单元。
Router 负责规划、协调与兜底；通用工具实现位于 tool-runtime，通过 tool-registry 暴露。
没有独立 General 或 Recommend 服务。User 负责账号、认证、权限，电商画像的业务所有者是 Consumer。

- 情绪推理默认预算 750ms；画像为可选增强，商品节点默认最多额外等待 500ms，同轮复用选择结果。
- MQ 使用 RabbitMQ 4.1 Quorum 普通/高优先级、预取 1、手工 ACK；不确定写操作进入核查，不自动重做订单操作。
- 商品当前要求、真实目录证据优先于历史画像；画像不能授权订单操作或补造价格、库存、优惠。
- PostgreSQL/pgvector 是持久化业务和向量主路径；Milvus SDK 的存在不证明生产已启用第二向量后端。
- ASR/TTS 已有独立语音接入；不能将未引入 DashScope starter 误写为禁止使用百炼模型 API。
- 监控配置存在不代表所有监控容器均已在生产运行。

## 画像与记忆治理

Consumer 的 `UserProfileService` + `UserProfileSnapshotStore` 是版本化电商画像主路径；
Redis 请求级投影是派生数据，不应反写成为第二事实源。
`EntityProfileService` 是 Redis 实体事实；`AgentMemoryService` 是按 Agent/用户隔离的文件记忆。
三者语义及调用入口不同，不通过简单复制或新增同名门面宣称已统一。
当前用户明确要求 > 已验证业务事实 > 有来源和时效的历史偏好；低可信记忆只可作为提示。
`UserProfileQueryService` 提供 PG 只读摘要，校验归属、schema、版本和可靠性；候选标记为未持久化。
三类来源统一输出历史参考边界与限长；旧文件未知时间不再视作新鲜。
Product/Order 不再向 Agent 发布接受模型 userId 的旧记忆工具；保留数据，电商写入仍走 Consumer。
完整用户级清除和跨进程文件并发仍属后续专项，禁止无证据合并历史数据。

## 开发与安全边界

- 生产前端为 `frontend/src`；`npm run dev` 只启动 Vite，`/api` 代理到 Gateway。
  `frontend/server` 是保留的旧 Node 原型，不属于生产构建或默认开发路径。
- 工具调用遵循当前 `ToolGateway` 和 Hook 约束；高风险操作必须保留鉴权、用户确认、幂等及审计。
- 提示词使用资源文件和现有渲染方法，沿用对应文件占位符语法；不要凭旧手册假定统一占位符。
- 禁止提交密钥、生产 Token 或连接凭据；日志及工具审计必须脱敏。
- 删除会话必须协调数据库与 Redis 占用，不能仅删 SQL 或批量清缓存。
- 默认单元测试隔离外部依赖。真实 PG/Redis/MQ 集成测试只连接专用测试实例，显式开启后故障必须失败，不能静默跳过。
- 覆盖率以 `pom.xml` 与工作流实际门槛为准，不声明未经测量的全项目 80%。PR 全部检查通过后才能合并。

## 深入资料

- [报告核对与分期整改](docs/assessment-follow-up-20260917.md)
- [画像来源与旧记忆隔离](docs/profile-memory-governance.md)
- [框架基线与兼容性决策](docs/adr/0001-framework-baseline.md)
- [发布与回滚检查单](docs/deployment-rollback-runbook.md)
- [可选画像与等待预算](docs/architecture/optional-user-profile.md)
- [严格 PG 集成验证](docs/rag-production/PG-INTEGRATION.md)
- [会话删除与占用恢复](docs/session-gate-deletion-fix.md)
