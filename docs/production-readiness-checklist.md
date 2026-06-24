# Production Readiness Checklist

> 基于对 `customer_work`（AgentScope Java 1.0.12）开源项目的 12 个生产级坑逐行分析，
> 结合 `ThinkingAgent` 架构设计文章的四层可靠性体系，对标 SmartAssistant 当前实现状态。
>
> 来源：
> - [从可运行代码到生产：中间隔着 12 个坑](https://mp.weixin.qq.com/s/Ihtqsp68m1h66Ua12yV7kw)
> - [从零设计一个准生产级 LLM Agent](https://mp.weixin.qq.com/s/UTEdhrkV3G3Ycfrg0Jng_A)

## 审计状态总览

| 类别 | 条目数 | ✅ 已通过 | ⚠️ 需关注 | ❌ 未通过 | 不适用 |
|------|--------|---------|-----------|---------|-------|
| 并发与状态 | 3 | 3 | 0 | 0 | 0 |
| 模型成本 | 2 | 2 | 0 | 0 | 0 |
| 召回质量 | 2 | 1 | 1 | 0 | 0 |
| 基础设施 | 3 | 1 | 2 | 0 | 0 |
| 安全与运维 | 2 | 1 | 1 | 0 | 0 |
| **合计** | **12** | **8** | **4** | **0** | **0** |

---

## 一、并发与状态（来自 customer_work 审计）

### 1.1 会话级并发串行化

**要求**：同会话请求串行执行，防止有状态 Agent 被并发请求同时写入。

| 条目 | 状态 |
|------|------|
| 有状态 Agent 是否被并发请求共享 | ✅ SmartAssistant 使用 Redis 管理会话（`SessionManagementService`），Agent 无状态化——每次请求新建 `SmartReActAgent` |
| 是否有 per-session 串行锁 | ✅ 会话状态存储在 Redis 中，业务层无状态，天然串行安全 |
| 是否存在写时竞态条件 | ✅ `ConversationDocumentService` 使用 `synchronized(getFileLock(sessionId))` + Caffeine 文件锁 |

**结论**：✅ 通过。架构差异（Spring MVC + 无状态 Agent vs WebFlux + 有状态 Agent）天然避免此问题。

---

### 1.2 热缓存有界性

**要求**：所有缓存必须有容量上限 + 过期策略。

| 条目 | 状态 |
|------|------|
| 语义缓存 | ✅ Redis（外置），TTL 86400s |
| 向量缓存 | ✅ Caffeine，`maximumSize()` + `expireAfterAccess()` |
| 文件锁缓存 | ✅ Caffeine，max 5000，30min 过期 |
| 用户意图分布 | ✅ Redis Hash，30 天 TTL |
| 决策审计日志 | ✅ Redis，7 天 TTL |
| 多 Agent 协作缓存 | ✅ SSE 事件，120s TTL |
| 是否存在无界 Map | ✅ 所有缓存均有界 |

**结论**：✅ 通过。

---

### 1.3 阻塞 IO 与线程模型

**要求**：阻塞操作不占用 IO 线程池，线程模型需匹配框架。

| 条目 | 状态 |
|------|------|
| 使用 Reactive 线程模型? | ✅ SmartAssistant 使用 **Spring MVC（Tomcat）**，非 WebFlux，无不匹配问题 |
| `@Async` 线程池是否有界 | ✅ `ThreadPoolConfig` 定义了 4 个有界线程池（`taskExecutor` / `scheduledTaskExecutor` / `asyncRouteExecutor` / `asyncEmbeddingExecutor`） |
| 拒绝策略是否合理 | ✅ `CallerRunsPolicy`（通用任务）/ `DiscardPolicy`（路由日志）/ `AbortPolicy`（Embedding） |

**结论**：✅ 通过。

---

## 二、模型层成本与正确性

### 2.1 流式响应正确性

**要求**：fallback 切换时不产生输出错乱。

| 条目 | 状态 |
|------|------|
| 流式 fallback 是否在首 token 到达后切换 | ✅ `inlineFallback()` 在 Agent 调用**之前**决定，不流式中途切换 |
| SSE 流式是否支持 cancel | ✅ `waitForDecisionFromRedis()` BLPOP 超时后返回 null，Consumer 处理为友好提示 |
| Agent 调用失败是否有兜底文案 | ✅ 4 套预设文案轮换（`FALLBACK_MESSAGES`） |

**结论**：✅ 通过。

---

### 2.2 重试区分错误类型

**要求**：只重试可恢复错误（限流/服务端/网络），4xx 快速失败。

| 条目 | 状态 |
|------|------|
| 工具层重试是否按类型过滤 | ⚠️ `ModelRoutingService.@Retry` 仅配了 `ConnectException`/`SocketTimeoutException`/`ResourceAccessException`，**已正确过滤 4xx**。但 Resilience4j `retry-exceptions` 列表以外异常不重试——这是框架行为，当前配置正确 |
| 是否已集成 ErrorRecoveryService | ✅ `SmartReActAgent` 6 个错误点 + `RouterService` catch 块已接入 |
| `AgentErrorCode` 枚举是否区分 retryable | ✅ 30 个枚举值均已标注 `retryable` |
| 是否存在无限重试 | ✅ 全部有最大次数限制（3 次或 2 次） |

**结论**：✅ 通过。**待确认**：`ModelRoutingService.@Retry` 的 `retry-exceptions` 列表是否完整覆盖了所有可能的网络异常（如 `UnknownHostException`、`SSLException` 等）。

---

### 2.3 多 Agent 成本护栏

**要求**：多 Agent fanout 有预算上限和超时控制。

| 条目 | 状态 |
|------|------|
| 多 Agent 并行执行是否有超时 | ✅ `agentTimeoutMs` 默认 30s |
| 是否有 Token 预算上限 | ✅ `SmartReActAgent` Token 预算追踪（`DEFAULT_TOKEN_BUDGET_RATIO = 0.8`） |
| 是否使用本地模型（成本固定） | ✅ `deepseek-r1:7b`（Ollama 本地），**无按 token 计费成本风险** |
| DAG 并行度是否有上限 | ✅ `parallelExecutor` 有界线程池 |

**结论**：✅ 通过。使用本地 Ollama 模型从根本上消除了文章所述的"cost amplification"问题。

---

## 三、召回质量

### 3.1 语义检索真实性

**要求**：不使用字符重合度伪语义检索。

| 条目 | 状态 |
|------|------|
| 语义搜索使用向量嵌入 | ✅ **BGE 384 维嵌入** + 余弦相似度 |
| 知识检索是否使用向量库 | ✅ `VectorCacheStore` + pgvector（`SemanticCacheService`） |
| 经验匹配使用什么 | ✅ **BGE 向量嵌入**（`ExperienceService.match()`） |
| 是否存在单字重合度伪语义 | ✅ 无。全文使用 BGE 或 TF-IDF 向量，非字符重合度 |

**结论**：✅ **反超文章推荐标准**。文章认为 BM25+分词是生产最低标准，本项目已使用真实语义嵌入。

---

### 3.2 限流与持久化外置

**要求**：限流和持久化不依赖进程内状态。

| 条目 | 状态 |
|------|------|
| 限流是否支持多副本 | ⚠️ `@RateLimiter`（Resilience4j）默认使用**进程内**存储，多副本部署额度会按倍数放大。当前单实例部署，无此问题。如未来水平扩展需迁移到 Redis+滑动窗口 |
| 长期记忆是否外置持久化 | ⚠️ `ConversationDocumentService` 存**本地文件**（`data/users/{userId}/memories/`），未使用外部存储。多副本场景下各实例文件独立 |
| Redis 是否承载会话状态 | ✅ 会话管理、语义缓存、审计日志全部使用 Redis |
| 异步持久化是否有重试 | ✅ 已修复（P12 fix：`ConversationDocumentService` 指数退避重试 3 次） |

**结论**：⚠️ **需关注**。当前单实例部署 OK，但 `ConversationDocumentService` 文件存储和进程内限流是水平扩展的瓶颈。

---

## 四、基础设施

### 4.1 提示词管理

**要求**：提示词热更新有版本控制和灰度。

| 条目 | 状态 |
|------|------|
| 提示词是否可热更新 | ❌ 本项目 prompt 写在 Java 常量中（`TaskAnalysisService.SYSTEM_PROMPT_TEMPLATE`、`QualityEvaluationService.EVALUATION_SYSTEM_PROMPT`），**不支持运行时热更新** |
| 提示词是否通过 Git 管理版本 | ✅ 所有 prompt 代码化，Git 管理全量历史，可回滚 |
| 是否存在灰度发布能力 | ❌ 不具备 |
| Nacos 是否配置了动态配置 | ❌ 项目连接了 Nacos，但未用于 prompt 管理 |

**结论**：⚠️ **需关注**。当前通过 Git 版本管理的方案优于热更不回滚，但如果需要运营实时修改 prompt，需引入 Nacos 动态配置 + 版本号 + 灰度开关。

---

### 4.2 优雅停机

**要求**：在途请求有机会完成再关闭。

| 条目 | 状态 |
|------|------|
| 是否有 `SmartLifecycle` 实现 | ❌ 未找到类似 `GracefulShutdownService` 的优雅停机组件 |
| Spring Boot 默认优雅停机是否启用 | ⚠️ `spring.lifecycle.timeout-per-shutdown-phase` 建议配置但不强制 |
| `@Async` 线程池是否等待完成任务 | ✅ `ThreadPoolConfig` 配置了 `setWaitForTasksToCompleteOnShutdown(true)` + `setAwaitTerminationSeconds(30)` |
| 数据库连接池是否在 Web 容器前关闭 | ❌ 未显式配置 shutdown 顺序 |

**结论**：⚠️ **需关注**。`@Async` 部分已有保障，但应用级别缺少 `SmartLifecycle` 编排各组件关闭顺序。

---

### 4.3 可观测性

**要求**：具备指标、日志、链路追踪。

| 条目 | 状态 |
|------|------|
| 是否有指标采集 | ✅ `AgentMetricsCollector` + Prometheus 指标（Micrometer） |
| 是否有链路追踪 | ✅ Jaeger 已接入 |
| 是否有错误审计日志 | ✅ `RouterService` 决策审计日志写入 Redis（7 天 TTL） |
| 是否有质量评估日志 | ✅ `QualityEvaluationService` 评估结果记录到日志 |
| 是否有错误恢复日志 | ✅ `ErrorRecoveryService.logRecovery()` |

**结论**：✅ 通过。

---

## 五、安全与运维

### 5.1 鉴权与会话归属

**要求**：敏感端点校验 sessionId 归属，密钥不硬编码。

| 条目 | 状态 |
|------|------|
| 是否有 JWT/API Key 鉴权 | ✅ `GlobalJwtAuthFilter` 统一 JWT 鉴权 |
| 会话中断/结束端点是否校验归属 | ✅ **已审计** — 项目中不存在 `endSession`/`interrupt` 端点，无 IDOR 风险 ✅ |
| 密钥是否从环境变量读取 | ✅ `.env` 文件 + `DotenvEnvironmentPostProcessor` |
| Swagger 在生产环境是否关闭 | ✅ 生产 profile 未配置 springdoc |
| API Response 是否暴露内部细节 | ✅ 异常信息已通用化 |

**结论**：✅ 已通过审计。

---

### 5.2 持久化可靠性

**要求**：持久化覆盖 cancel/error/complete 三种完成状态，有重试和背压。

| 条目 | 状态 |
|------|------|
| 异步持久化是否支持重试 | ✅ **已修复**（指数退避 3 次，`0cc6589`） |
| 是否使用 `doFinally` 而非 `doOnComplete` | ✅ Spring MVC 无 Reactor，`@Async` 方法由 Spring 管理完成状态 |
| 同一操作是否有唯一写入路径 | ✅ `ConversationDocumentService` 是唯一会话记忆写入入口 |
| 持久化是否有背压 | ⚠️ `taskExecutor` 队列 200，超过后 `CallerRunsPolicy` 降级（调用者线程执行，提供隐式背压） |

**结论**：✅ 通过（已修复 P12）。

---

## 六、ThinkingAgent 补充项

除 customer_work 的 12 条外，ThinkingAgent 文章强调了以下 Agent 架构层面的可靠性要求：

### 6.1 检查点机制

| 条目 | 状态 |
|------|------|
| 是否有 Checkpoint 持久化 | ❌ **完全无**。任务执行中间状态不保存 |
| 长任务是否可断点续跑 | ❌ 不可 |

**结论**：❌ **未实现**。但对 SmartAssistant 的短交互场景（单轮/少轮对话）影响较小。如引入多步审批流（Order 场景）可考虑。

---

### 6.2 任务分析层

| 条目 | 状态 |
|------|------|
| 是否有结构化任务分析 | ✅ **已实现**（`TaskAnalysisService`，核心#8） |
| 实体/约束/风险/工具评分是否提取 | ✅ |
| 是否影响路由决策 | ⚠️ 当前仅存储到 Redis，未用于路由优化 |

**结论**：✅ 核心已实现，后续可路由决策增强。

---

### 6.3 标准化错误码与表驱动恢复

| 条目 | 状态 |
|------|------|
| 是否有统一错误码枚举 | ✅ **已实现**（`AgentErrorCode` 30枚举值，核心#10） |
| 是否有表驱动恢复路由器 | ✅ **已实现**（`ErrorRecoveryService`） |
| 是否在所有工具中统一使用 | ✅ 45处调用已全部迁移 |

**结论**：✅ 已实现。

---

### 6.4 质量评估

| 条目 | 状态 |
|------|------|
| 是否有 LLM-as-Judge 质量评估 | ✅ **已实现**（`QualityEvaluationService`，核心#11） |
| 是否有规则级快速过滤 | ✅ `ReflectionService` |
| 评估失败是否有恢复策略 | ⚠️ 当前仅跳过缓存，不触发重试 |

**结论**：✅ 核心已实现，重试机制待后续增强。

---

## 七、行动项优先级

按高/中/低标注，已修复的标记为 ✅：

| 序号 | 条目 | 优先级 | 状态 | 对应文件 |
|------|------|--------|:----:|---------|
| 1 | 确认 `endSession` 端点归属校验 | 🟢 **高** | ✅ **已审计 — 无此类端点，无 IDOR 风险** | — |
| 2 | 配置 `spring.lifecycle.timeout-per-shutdown-phase` | 🟢 **高** | ✅ **已配置** | 8 个 `application.yml` |
| 3 | 评估长对话记忆（文件→外置存储） | 🟡 **中** | ✅ **已增加 Redis 镜像**（`app.memory.redis-enabled=true`） | `ConversationDocumentService` |
| 4 | 引入 Nacos 动态 prompt（如需） | 🟡 **中** | ✅ **已增加 @RefreshScope + 可外部化 prompt 属性** | `TaskAnalysisService` / `QualityEvaluationService` |
| 5 | 任务分析结果驱动路由增强 | 🟡 **中** | ❌ 待处理 | `RouterService` |
| 6 | 质检不通过时触发重试 | 🟡 **中** | ❌ 待处理 | `RouterService.finalizeRouting()` |
| 7 | Checkpoint 断点续跑（Order 多步流） | ⚪ **低** | ❌ 待评估 | `GraphExecutionService` |
| 8 | 水平扩展适配（进程内限流→Redis） | ⚪ **低** | ❌ 待评估 | — |
| 9 | **Tool Group 工具分组** | 🟡 **中** | ✅ **已实现** | `ToolGroupManager` + `SmartReActAgent` |
| 10 | **结构化输出框架化** | 🟢 **高** | ✅ **已实现** | `StructuredOutputService` |
| 11 | Runtime Sandbox 工具隔离 | ⚪ **低** | ❌ 待评估 | — |
| 12 | Harness 工作区 | ⚪ **低** | ❌ 待评估 | — |
| 13 | 评估与强化学习管线 | ⚪ **低** | ❌ 待评估 | — |

---

*本文档基于 2026-06-23 项目版本（commit 0cc6589）生成。*
*上次审核：2026-06-23*
