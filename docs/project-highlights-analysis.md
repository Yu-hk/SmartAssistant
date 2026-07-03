# SmartAssistant 项目功能亮点分析

> 分析日期：2026-07-03
> 项目定位：基于 Spring AI 2.0.0 + Spring Boot 3.4.8 的多智能体客服对话系统
> 代码规模：9 个微服务，~45 个核心模块

---

## 一、架构概览

```
┌─────────────────────────────────────────────────────────┐
│                    前端 (React + TDesign)                 │
└──────────────────┬──────────────────────────────────────┘
                   │ SSE / EventSource
┌──────────────────▼──────────────────────────────────────┐
│          Gateway (8081) — JWT 鉴权 + 路由转发             │
└──────────────────┬──────────────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────────────────┐
│        Consumer (8082) — SSE 入口 + 流控 + 会话管理       │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌────────────┐ │
│  │三层流控   │ │停��取消   │ │SSE续传   │ │AgentEventBus│ │
│  │L1/L2/L3  │ │/cancel   │ │SortedSet │ │RPUSH/BLPOP │ │
│  └──────────┘ └──────────┘ └──────────┘ └────────────┘ │
└──────────────────┬──────────────────────────────────────┘
                   │ HTTP
┌──────────────────▼──────────────────────────────────────┐
│          Router (8083) — 智能路由 / DAG 执行引擎           │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌────────────┐ │
│  │TaskPlanner│ │GraphExec │ │Handoff   │ │Experience  │ │
│  │DAG 编排  │ │重试/重规划│ │同步/异步 │ │BGE语义合并 │ │
│  └──────────┘ └──────────┘ └──────────┘ └────────────┘ │
└──┬────────────┬────────────┬────────────┬───────────────┘
   │            │            │            │
┌──▼──┐   ┌────▼────┐ ┌────▼────┐ ┌─────▼─────┐
│Order│   │ Product │ │ General │ │ Recommend │
│8085 │   │  8084   │ │  8087   │ │   8088    │
└─────┘   └─────────┘ └─────────┘ └───────────┘
```

---

## 二、核心亮点分域分析

### 领域 1：智能路由与多 Agent 协作

| 特性 | 亮点描述 | 技术实现 |
|:----|:---------|:---------|
| **14 步决策流水线** | 从经验匹配到内联兜底，完整覆盖 14 步 | Step 0~6 流水线架构 |
| **三路并行意图融合** | 规则(7μs) ∥ 小模型(10ms) ∥ LLM(200ms)，预期减少 90% LLM 调用 | L3 IntentFusionService |
| **DAG 图分解执行** | 任务拆解为有向无环图，无依赖节点并行、有依赖顺序 | TaskPlannerService + GraphExecutionService |
| **指数退避重试** | RETRYABLE 错误最多重试 3 次(1s→2s→4s) | GraphExecutionService |
| **验收标准检查** | LLM 判断输出是否满足 successCriteria，不满足触发重规划 | ReflectionService.checkCriteria() |
| **Handoff 显式交接** | Agent A → Agent B 显式移交，支持同步 HTTP 和异步 EventBus 双模式 | HandoffCommand + AgentEventBus |
| **BGE 语义合并** | Jaccard 去重(0.35) + BGE 余弦(0.85) 双通道，Milvus/pgvector/全量三路降级 | ExperienceService.findSimilarByBGE() |

### 领域 2：RAG 知识库

| 特性 | 亮点描述 | 技术实现 |
|:----|:---------|:---------|
| **多格式文档解析** | PDF/Word/HTML/TXT 自动路由 | DocumentParseRouter |
| **双策略语义分块** | SemanticChunkStrategy + RecursiveChunkStrategy fallback | DocumentChunker |
| **5 路召回 + RRF 融合** | 精确匹配 + 关键词 + BM25 + 经验 + 图谱 | ProductRagService |
| **3 级检索策略** | LIGHT(2路) / STANDARD(3路+RRF) / DEEP(5路+Rerank) | TieredRetrievalService |
| **版本控制** | `isSupersededBy()` 自动淘汰旧版，评分含 versionBoost | KnowledgeDocument |
| **ACL 租户隔离** | 检索前过滤，非检索后过滤 | three KnowledgeBase implementations |
| **变更检测闭环** | SHA-256 contentHash + HashUtil.normalizeText() 跳过未变更文档 | ContentHashCache + HashUtil |
| **先删后增** | `removeByBaseDocId()` 三实现(InMemory/PgVector/Milvus) | KnowledgeBase interface |
| **重排序器** | bge-reranker-v2-m3 ONNX，SafeReranker 异常隔离 | Reranker |
| **知识图谱** | 15 节点/36 边商品关系图，查询 1.947μs | ProductGraphService |

### 领域 3：工具治理与安全

| 特性 | 亮点描述 | 技术实现 |
|:----|:---------|:---------|
| **统一工具注册** | 38 个 @Tool 全覆盖注册 | ToolRegistry |
| **4 级风险等级** | READ / LOW / MEDIUM / HIGH，9 个高风险需审批 | ToolRiskLevel |
| **工具执行网关** | 鉴权(Scope) → 熔断(3次) → 限流(令牌桶) → 幂等 → 审计 | ToolGateway |
| **二阶段确认** | 退款等敏感操作 PENDING→CONFIRMED→CONSUMED 状态机 | ApprovalService |
| **脚本沙箱** | 黑名单 + 资源限制 + 超时熔断(2s)，全参数外部化 | ScriptSandbox |
| **AST 级 SQL 防护** | jsqlparser 表名白名单校验 | SqlSecurityValidator |
| **错误分类监控** | retryable/fatal 分类 + 延迟 p50/p95/p99 | ToolLogAspect + NewMetricsCollector |

### 领域 4：SSE 流式与用户体验

| 特性 | 亮点描述 | 技术实现 |
|:----|:---------|:---------|
| **三层请求排队** | L1 全局(5) → L2 会话级(1) → L3 优先级队列 | RequestQueueService |
| **SSE 断线续传** | id:seqNo 注入 + Sorted Set 缓冲 + ZRANGEBYSCORE O(log n) | StreamChatController |
| **请求取消** | 前端关闭 EventSource → 后端释放槽位 + 取消端点冗余 | handleStop + /chat/cancel |
| **排队状态推送** | 实时推送 queued/queue_position/processing 事件 | StreamChatController |
| **多 Agent SSE 事件** | 并行 Agent 的执行过程通过 Redis List 汇聚转发 | RouterService.storeSseEvent() |
| **EventSource 自动重连** | 浏览器原生自动重连 + Last-Event-ID 协议 | 前端 useChat.ts |

### 领域 5：记忆与经验体系

| 特性 | 亮点描述 | 技术实现 |
|:----|:---------|:---------|
| **用户画像时间衰减** | weight×e^(-λ·days)，30天旧偏好降至 74% | UserProfile |
| **Agent 独立记忆** | Order/Product/General 各 Agent 拥有用户粒度记忆文件 | savePreference/recallMemories |
| **后台自动提取偏好** | 每轮对话后 LLM 自动提取可复用偏好 | MemoryExtractor |
| **上下文感知排序** | 按问题关键词匹配排序记忆条目 | getAllFormatted() |
| **经验验证+淘汰** | 时效性/可靠度/新鲜度三指标，每日定时扫描清理 | ExperienceValidator + ExperiencePruner |
| **9 段式 Context 压缩** | 增量更新 + MicroCompact + PrecomputedCompact | ContextCompressionService |
| **实体画像** | 跟踪用户交互中的关键实体(订单/商品/金额等) | EntityProfileConfig |

### 领域 6：可观测性与运维

| 特性 | 亮点描述 | 技术实现 |
|:----|:---------|:---------|
| **全栈监控** | Prometheus + Grafana + Jaeger + Loki 四件套 | monitoring/ |
| **20+ 自定义指标** | Token 统计、错误分类(p50/p95/p99)、工具延迟、路由决策等 | 各模块 MetricsCollector |
| **Jaeger 嵌套 Span** | agent-llm-call + agent-tool-execute 嵌套追踪 | TraceSpan |
| **Span 级请求追踪** | MDC 透传 requestId，Consumer → Router → Agent → @Tool | DistributedTracingService |
| **Grafana 仪表盘** | 10 个自定义面板(含 ErrorType 饼图、延迟分位数等) | grafana-dashboard.json |

---

## 三、亮点总结（Top 10）

| 排名 | 亮点 | 关键指标 | 为什么强 |
|:---:|:----|:--------|:---------|
| 🥇 | **三路并行意图融合** | 减少 ~90% LLM 调用 | 规则 7μs + 小模型 10ms 替代 LLM 200ms |
| 🥇 | **完整 DAG 执行引擎** | 重试+验收+重规划闭环 | 不是简单的 while 循环，而是可恢复的生产级编排 |
| 🥉 | **BGE 语义合并 + 经验体系** | cosine≥0.85 自动合并 | Jaccard+BGE 双通道降级，Milvus/pgvector/全量三路 |
| 4 | **SSE 断线续传** | O(log n) 续传查询 | Hash→Sorted Set 改造，EventSource 自动重连 |
| 5 | **工具全生命周期治理** | 38 工具全注册 | 注册→鉴权→熔断→限流→幂等→审计→监控 |
| 6 | **三层流控** | L1 全局/L2 会话级/L3 优先级 | 单体→分布式扩展无架构障碍 |
| 7 | **14 步决策流水线** | 完整覆盖不遗漏 | 从经验匹配到内联兜底，每个步骤可观测 |
| 8 | **生产级 RAG 管线** | 5路召回+RRF+版本+ACL | 非 Demo 级 RAG，全生产字段覆盖 |
| 9 | **请求取消** | 节省 10~20s/次 GPU | 前端→Consumer→Agent 全链路取消 |
| 10 | **全栈可观测** | 20+ 指标 + Jaeger + Loki | 每次路由的完整决策链可查询 |

---

## 四、改进空间（低 ROI 但可感知）

| 方向 | 现状 | 可改进 |
|:----|:-----|:-------|
| 分布式信号量 | 进程内 Semaphore | Redis 分布式信号量（多实例时必需） |
| E2E 集成测试 | 部分覆盖 | 完整覆盖新增功能（SSE续传/重规划/流控） |
| 前端 TS 编译 | 预存错误 | AgentConfigDialog/Header/NewChatDialog 类型修复 |
| Token 实时回传 | 后台已统计 | SSE 事件回传 token 消耗到前端 |
| Grafana 告警 | 指标已有 | 配置 AlertManager 告警规则 |
