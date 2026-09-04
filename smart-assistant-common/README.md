# SmartAssistant Common 模块边界

`smart-assistant-common` 只承载两个以上业务模块共享的契约和基础能力，不承载单一业务模块的配置、领域模型、控制器或任务编排。

## 包分类

| 分类 | 包 | 职责 |
|---|---|---|
| Agent 运行时 | `agent`、`memory`、`model`、`prompt`、`skill` | ReAct 执行、上下文压缩、模型分层、提示词基础组装和 Skill 装配 |
| 工具与治理 | `gateway`、`tool`、`governance`、`correction` | 工具发现/执行、调用预算、安全钩子、工具纠错 |
| 知识与检索 | `rag`、`embedding`、`tokenizer` | 文档解析、索引、检索、重排、Embedding 和分词 |
| 安全与可观测性 | `security`、`audit`、`observability`、`metrics`、`tracing`、`interceptor` | PII、审计、指标、链路上下文和服务观测 |
| 质量保障 | `quality`、`eval` | 多 Agent 质量协议和 CI/离线评测能力 |
| 基础契约 | `cache`、`error`、`exception`、`recovery`、`response`、`sql`、`util`、`json` | 跨模块缓存版本、错误模型、响应模型和通用安全工具 |

## 归属规则

1. 只被 Consumer、Router、Product 或 Order 中一个模块使用的代码，放回对应模块。
2. 业务数据模型、领域状态、Web 控制器和模块配置不得放入 Common。
3. Common 自动配置统一登记在 `AutoConfiguration.imports`，并使用条件装配和用户 Bean 回退。
4. Spring AI、Spring Boot、Micrometer 或 MCP SDK 已提供等价能力时，不在 Common 维护重复抽象。
5. 仅测试引用且不承担 CI/评测职责的生产类应删除；评测工具集中在 `eval` 分类。

## 当前模块归属

- Consumer：数据库方言、用户反馈、情绪分析、SSE 会话流。
- Router：任务预算、意图标签、Agent 调度队列。
- Product：RAG 查询管线和商品知识图谱装配。
- Order：订单状态与订单幂等执行。

新增公共能力前，应先确认至少有两个生产模块使用；否则默认放在业务模块内部。
