# SmartAssistant — 多智能体对话系统

[![Java](https://img.shields.io/badge/Java-21%2B-blue)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.8-brightgreen)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.0-brightgreen)](https://docs.spring.io/spring-ai/reference/)
[![React](https://img.shields.io/badge/React-18-61DAFB)](https://react.dev/)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)
[![Production Readiness](https://img.shields.io/badge/production_readiness-audited-blue)](docs/production-readiness-checklist.md)

> 基于 Spring AI 2.0.0 + 多智能体协作平台，集成 **DeepSeek V4-Flash 云端推理**（通用对话/订单/商品 Agent）+ **本地 Ollama 推理引擎**（qwen2.5:7b 主模型/Router 路由）+ **轻量 Ollama 通道**（qwen2.5:3b 辅助任务）。
> 支持多 Agent 协同、三层路由兜底、订单查询、商品咨询、语义缓存、全链路监控。

---

## 目录

- [项目简介](#项目简介)
- [核心亮点](#核心亮点)
- [系统架构](#系统架构)
- [技术栈](#技术栈)
- [服务说明](#服务说明)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
  - [1. 启动基础设施](#1-启动基础设施)
  - [2. 配置环境变量](#2-配置环境变量)
  - [3. 构建项目](#3-构建项目)
  - [4. 启动服务](#4-启动服务)
- [详细部署流程](#详细部署流程)
- [RAG 召回管道](#rag-召回管道)
- [Router 反思器](#router-反思器)
- [@Tool 统一日志切面](#tool-统一日志切面)
- [语义缓存](#语义缓存)
- [请求排队](#请求排队)
- [配置说明](#配置说明)
- [监控体系](#监控体系)
- [API 文档](#api-文档)
- [常见问题](#常见问题)
- [开发指南](#开发指南)

---

## 项目简介

SmartAssistant 是一个多智能体对话系统，基于 **Spring AI** 框架和 **自研 ReAct 循环**实现。系统通过 **智能路由器** 将用户请求分发到不同的专业 Agent，支持：

- **Order Agent**：订单查询、退款处理、物流跟踪、优惠券查询、知识库检索
- **Product Agent**：商品查询、库存查询、价格查询、知识库检索
- **General Agent**：闲聊陪伴、问答、新闻热点、天气查询、单位转换、汇率转换
- **Embedding Service**：本地 BGE ONNX 嵌入服务（1024维），供语义缓存、知识库检索和经验匹配
- **Router 决策链路**：经验匹配(TOOL/COMMON) → 语义缓存(T1/T2/T3) → 任务分析(意图/实体/约束/风险/工具评分) → 规则层评测后处理(实体归一/词槽/澄清/置信度) → 意图查询改写 → DAG 多Agent协作 → inlineFallback(三级兜底)
- **Consumer 聚合**：统一对话入口，上下文管理、用户画像、会话记忆沉淀

采用本地推理架构（Ollama + ONNX Runtime），零外部 API 依赖。通过 **Prometheus + Grafana + Jaeger + Loki** 实现全链路可观测性。

---

## 核心亮点

| 特性 | 说明 |
|------|------|
| 🧠 **多 Agent 协作** | 9 个微服务通过 Nacos 自动发现与注册。Router 支持任务分解 → 并行调用 → 结果合并，跨域问题自动分配多个 Agent |
| 🧠 **三层语义缓存 + BGE ONNX + 智能跳过** | 精确匹配 → 关键词哈希 → BGE 向量匹配。短时效 Agent(TTL<1h)回复自动跳过缓存，管理员工具操作通过 Redis 标记跳过，均保留路由决策加速 |
| 🔍 **12 维 Agent 可标注评测** | LLM 输出后自动执行 6 层规则后处理：实体归一化(日期/金额/地点/纠错) → 词槽状态机(6意图词槽表+缺失/冲突检测) → 澄清判断(追问生成) → 输入鲁棒性(错别字/别名纠错)。叠加 LLM 层 6 维（意图识别、多意图拆分、隐含意图、拒识、实体识别、工具评分），完整覆盖文章推荐的 12 个评测维度 |
| 🎯 **意图置信度 + 阈值路由** | LLM 输出意图置信度(0.0~1.0)，低于 0.6 阈值时自动触发澄清流程而非盲目执行，避免误操作 |
| 🔄 **意图引导的查询改写** | 任务分析后根据意图类型选择改写策略：多意图→查询分解、模糊→查询扩展(补充实体)、精确→保留、对话→指代消解 |
| 🗂️ **多样性 RAG + 生产级增强** | PDF/Word/HTML 文档解析路由 + 语义/递归分块(Chunking) + 5路召回+RRF融合 + BM25混合评分 + 分级检索策略(LIGHT/STANDARD/DEEP) + 版本控制(自动淘汰旧版) + ACL 租户隔离 + INSUFFICIENT_EVIDENCE 规范拒答 |
| 🧩 **P0 LLM 调用网关** | `AgentLLMGateway` 统一封装 ChatClient 调用，内置超时(虚拟线程Future.get)、指数退避重试、熔断保护(5次失败→30s恢复)、Token 计量。与模型 SDK 解耦(LLMExecutor函数式接口) |
| 🔑 **P0 统一工具治理** | `ToolRegistry` + `ToolGateway`：工具注册→鉴权(Scope)→熔断(3次失败)→限流(令牌桶)→幂等(IdempotencyKey)→审计日志全链路。38 个 @Tool 全部注册，9 个高风险工具需审批 |
| 🔍 **P1 Agent 执行状态机** | 10 状态(→WAITING_TOOL/WAITING_APPROVAL/FAILED/DEAD_LETTER) + 11 事件类型。`AgentEventBus` Redis List 持久化执行轨迹，`GET /events/{requestId}` 端点可查询每次路由的完整决策链 |
| 📋 **P1 子任务错误分类（ErrorType）** | SubTaskResult 增加 NONE/RETRYABLE_FAILED/FATAL_FAILED/NEED_REPLAN 四状态错误分类。GraphExecutionService 根据 ErrorType 差异化处理：RETRYABLE → 指数退避重试(1s→2s→4s, 最多3次), FATAL → 记录跳过, NEED_REPLAN → 触发重规划生成新子任务图 |
| ✅ **P1 验收标准（successCriteria）** | SubTask 新增 successCriteria 字段，TaskPlanner prompt 升级为5字段格式(id|desc|agent|deps|验收标准)。ReflectionService 新增 checkCriteria()，LLM 判断输出是否满足验收标准，不满足时标记 NEED_REPLAN |
| 🔄 **P1 重规划机制** | GraphExecutionService 每轮执行完毕后检查 NEED_REPLAN 节点，调用 TaskPlannerService.replan() 生成新子任务图，通过 IntentGraph.addNodes() 动态追加到执行队列 |
| ⏱️ **P1 用户画像时间衰减** | UserProfile 新增指数衰减模型 weight×e^(-λ·days)，30天后旧偏好降至 74%。支持自定义衰减率参数，clamp≥0.1 防止零权重 |
| 🔗 **P2 BGE 语义合并（Semantic Merge）** | ExperienceService 在 Jaccard 关键词去重（0.35）基础上增加 BGE 向量语义合并（cosine≥0.85）。Milvus/pgvector/全量遍历三路降级，自动合并语义等价但关键词不同的经验条目 |
| 📜 **P2 摘要回指（Back-Reference）** | RouterRagService 从对话历史提取 ORDER_ID/PRODUCT/AMOUNT/DATE/ACTION/DECISION 六类实体，生成结构化回指文本 `【历史引用】` 注入增强问题。配置项 `backref-enabled=true` |
| 📊 **P2 监控体系增强** | ToolLogAspect 和 NewMetricsCollector 新增 3 类指标：错误分类计数器(retryable/fatal)、延迟百分位 Timer(p50/p95/p99)、按工具+错误类型的 `a2a_tool_error_type` Counter |
| 🔍 **P0 RAG 变更检测闭环** | KnowledgeIngestionService 集成 ContentHashCache + HashUtil，摄入前按 baseDocId 计算聚合 SHA-256 哈希，通过 `normalize_text()` 过滤时间戳/页脚假变更，未变更文档跳过解析/分块/嵌入/入库全链路 |
| ⚡ **P2 关键词快车道** | 高频明确意图(退款/查订单/问候等)跳过 LLM 分诊直接路由。**7.3μs/次**，吞吐量 132,287 次/秒，加速比 ~68,000x |
| 🗣️ **P2 对话阶段感知** | `RouterStageAwareService` 四阶段(GREETING/COLLECTING/PROCESSING/CONFIRMING)聚焦 Prompt 注入，集成到 TaskAnalysisService。降低 Token 消耗，减少跨阶段上下文干扰 |
| 🔗 **P3 商品知识图谱** | `ProductGraphService` 15 节点/36 边的内存商品关系图(同类/配件/替代/升级/互补)。`ProductRagService` Path 5 集成。查询延迟 **1.947μs** |
| 🔄 **P0 SSE 断线续传** | Consumer `forwardSSE()` 逐行解析 SSE 事件 → 注入 `id: seqNo` → Redis Sorted Set 缓冲(`ZADD` score=seqNo)。EventSource 自动重连 + `Last-Event-ID` 协议。续传查询 O(log n) (`ZRANGEBYSCORE`) |
| 📤 **P1 异步 Agent 事件总线** | `AgentEventBus` 基于 Redis List 的异步通信(RPUSH+BLPOP)，Router 发布事件到 `agent:events:{agentName}`，Agent 虚拟线程异步消费。失败自动重新入队。通过 `router.graph.async-handoff-enabled` 启用 |
| 🚦 **P2 三层请求排队流控** | L1: 全局 Semaphore 并发控制(默认5)。L2: 会话级并发(每sessionId最多1请求)。L3: PriorityBlockingQueue 优先级调度(`queue.priority-enabled`)。配置 `queue.session-max-concurrency` |
| 🔗 **P3 跨模块推荐服务** | 新增 `smart-assistant-recommend`(端口 8088)，Feign 调用 Product(图谱)+Order(购买历史)。三策略：图谱推荐→协同过滤→热门兜底。推荐引擎延迟 **0.120ms** |
| 🧩 **L3 三路并行意图融合** | 规则(KeywordFastRoute, 7μs) ∥ 小模型(BGE余弦分类器, 10个意图类, 10ms) ∥ LLM(TaskAnalysis, 200ms) 三路并行融合。融合策略：规则≥0.9直接输出→小模型≥0.7优先→LLM兜底。预期减少 **~90% LLM 调用** |
| 🔄 **L5 意图漂移检测** | 多轮对话中检测用户意图漂移：BGE 余弦相似度对比当前轮与上轮用户问题。双阈值：<0.80 漂移(触发新意图识别)、<0.55 强漂移(标记上下文需重置)。集成到 Router Step 3.5 |
| 🔀 **Step 0 多意图直接并行调度** | 经验匹配检测到 multi-intent 时(secondaryIntents非空)，不再走 LLM 管道，直接虚拟线程并行调度多个 Agent 并合并结果。延迟从 `经验匹配+LLM×2+Agent串行` 降为 `经验匹配+max(Agent₁,Agent₂)` |
| ⚙️ **P4 Hot Agent + 调度系统** | 基于 Redis List 的异步任务队列(LPUSH/BRPOP)，虚拟线程 Worker 池，Hot Agent 预热(Order/Product/General/Recommend 常驻内存+并发限流)。`scheduler.enabled=true` 开启，默认关闭 |
| 🛡️ **AST 级 SQL 防护** | 基于 jsqlparser 的表名白名单校验，精确到 SQL AST 节点，杜绝注入 |
| 🔍 **@Tool 统一日志切面** | AOP 拦截全部 @Tool 方法，自动记录 requestId + 输入参数 + 输出结果 + 执行耗时；requestId 通过 MDC 全链路透传（Consumer → Router → Agent → @Tool） |
| 🔄 **二阶段质量评估** | 反射器(ReflectionService)纯规则五维评分 + LLM-as-Judge(QualityEvaluationService)四维语义评估；反射器通过后仅在边界区间触发 LLM 质检，平衡质量与开销。质量阈值从 0.05 提升至 0.30，拒答统一为 INSUFFICIENT_EVIDENCE 结构化格式 |
| 📋 **标准错误码 + 表驱动恢复** | `AgentErrorCode` 统一枚举 32 个错误码(6 分类)，`ErrorRecoveryService` 三态表驱动恢复路由(RETRY/RETRY_BACKOFF/CLARIFY_USER/FALLBACK_AGENT/TERMINATE)；集成到 SmartReActAgent 6 个错误点 + 全工具 45 处调用 |
| 🔄 **Agent 级工具自动重试** | `executeToolCallWithRetry()` 统一并行/串行工具执行路径，解析 error_code JSON 自动重试，退避策略由 ErrorRecoveryService 表驱动决策 |
| 📊 **全栈可观测** | Micrometer + Prometheus + Grafana 指标，Jaeger 链路追踪（含嵌套 Span：`agent-llm-call` + `agent-tool-execute`），Loki 日志聚合，10 个自定义仪表盘（含 Token 累计 Stat + 业务仪表盘 Token 面板）；Order/Product/General 各服务独立暴露 `a2a_llm_token_input_total` / `a2a_llm_token_output_total` 等 8 类 Agent 指标。新增 **错误分类监控**（retryable/fatal 分位数 + 延迟 p50/p95/p99） |
| ⏳ **请求排队 + SSE 流式** | Semaphore 限流 LLM 并发(默认5)，排队时 SSE 实时推送位置，支持 thinking/tool_call/response 事件 |
| 🐳 **容器化部署** | Dockerfile + docker-compose.deploy.yml，7 个服务一键构建部署 |
| 🔧 **自研 ReAct 循环** | SmartReActAgent 实现全可控 ReAct 循环，内置迭代限制(10轮)、超时保护(60s)、Token 预算追踪(80%)、上下文压缩(9段式摘要+增量更新+MicroCompact) |
| 🔄 **并行工具执行** | LLM 一次返回多个 tool_call 时自动并行执行(最大4并发)，独立超时，按序收集结果 |
| 🛡️ **工具接口四步法** | Schema 收窄(enum/required/regex) → 错误结构化(ToolResult) → Description 互斥 → 副作用审批(confirmAction 二阶段确认) |
| ⏱️ **Runtime Sandbox 脚本沙箱** | executeScript 三层防御：关键字黑名单 → 静态资源限制(长度/行数/变量数/单行/输出) → 超时熔断(独立虚拟线程 + Future.get(2000ms))。全部参数外部化配置 `sandbox.script.*` |
| 🎯 **意图定义向量化检索** | 5 个意图定义(ORDER/PRODUCT/GENERAL/COMPLEX/UNKNOWN)由 BGE 向量动态检索 Top-3，替代全量硬编码。BGE cosine 相似度(主)+关键词命中加权(辅)，不可用时降级纯关键词匹配 |
| ✅ **经验验证+淘汰机制** | ExperienceValidator 对召回经验验证时效性(>24h未命中标记过时)、可靠度(hitCount<3标记低置信)、新鲜度(未经验证)。ExperiencePruner 每日定时扫描淘汰低质量经验 |
| 🔀 **Handoff 显式交接** | Agent 执行完毕后可通过 `HandoffCommand(targetAgent, question, contextPayload)` 显式移交下一个 Agent，支持递归 Handoff 链。适用于客服转接等串行场景 |
| 🧠 **Agent 独立记忆** | Order/Product/General 各 Agent 拥有用户粒度的独立记忆文件(`{agent}-memory.md`)，通过 `savePreference`/`recallMemories` 工具存取偏好。Markdown 格式，与 Consumer 记忆系统一致 |
| ⏰ **记忆老化警告+条目截断** | 超过 7 天记忆标记 ⚠️、超过 30 天 ⚠️⚠️；超过 10 条时输出 key-only 索引并提示"可调用 recallMemories 获取详情"。Agent prompt 统一增加验证指令 |
| 🕵️ **后台自动提取偏好** | 每轮对话结束后，MemoryExtractor 异步扫描用户输入+Agent 回复，用 LLM 自动提取可复用偏好写入记忆。不限 Agent 手动调用 |
| 🔗 **Span 级嵌套追踪** | TraceSpan 基于 Micrometer Observation 为 LLM 调用(`agent-llm-call`)和工具执行(`agent-tool-execute`)创建 Jaeger 嵌套跨度，与现有 0.3 采样率配合 |
| 📝 **9 段式 Context 压缩** | 9 个结构化章节摘要(诉求/技术/错误/待办等)；增量更新避免重复压缩；MicroCompact 清理旧工具结果；PrecomputedCompact 后台异步预压缩 |
| 🔍 **上下文感知记忆排序** | `getAllFormatted(agent, userId, context)` 按问题关键词匹配排序记忆条目，最相关偏好优先展示 |
| 🔍 **Milvus 向量数据库** | 替代 pgvector，Milvus 集群(etcd+MinIO+Milvus)提供知识库持久化 + 经验向量检索(IVF_FLAT + COSINE)。Milvus 不可用时自动降级 pgvector。SHA-256 截断 64 位哈希替代 hashCode 避免 ID 冲突 |
| 🔒 **向量库 6 类生产字段** | 参考字节面试考点：Milvus/InMemory/PgVector 三个知识库全部补齐 ACL(tenant_id)、版本号(version)、来源(source_url)、段落索引(chunk_index)、更新时间(updated_at)、content_hash。每类字段对应一种生产事故，ACL 检索前过滤强制执行 |
| 💾 **对话摘要持久化到 Milvus** | 方案 A：每次 Context 压缩生成的 9 段式摘要自动嵌入为 BGE 向量，持久化到 Milvus `conversation_summaries` Collection。支持语义检索历史对话摘要 |
| 🧩 **System Prompt 分层** | PromptBuilder 三层组装: base-prompt(通用规则) + 服务自有指令 + 运行动态上下文 |

---

## 系统架构

```text
┌─────────────┐     ┌──────────────┐     ┌───────────────────────────┐
│   Frontend  │────▶│   Gateway    │────▶│        Router             │
│  React:3001 │     │  :8081 (JWT) │     │  :8083 (意图识别           │
└─────────────┘     └──────────────┘     │  + 任务分析                │
                                          │  + 评测后处理              │
                                          │  + 查询改写                │
                                          │  + DAG 协作               │
                                          │  + 关键词快车道            │
                                          │  + 阶段感知 Prompt)        │
                                          └──────────┬────────────────┘
                                                   │
                          ┌─────────────────────────┼──────────────┐
                          │                         │              │
                    ┌─────▼──────┐          ┌───────▼──────┐      │
                    │  Consumer  │          │   General    │      │
                    │  :8082     │          │   :8087      │      │
                    │ (会话管理   │          │  (闲聊+天气   │      │
                    │  记忆沉淀)  │          │   换算工具)   │      │
                    └─────┬──────┘          └──────────────┘      │
                          │                                        │
               ┌──────────┼──────────┐                             │
          ┌────▼────┐ ┌──▼────┐ ┌───▼────┐   ┌───────────────┐    │
          │  Order  │ │Product│ │  User  │   │  Recommend    │    │
          │  :8085  │ │:8084  │ │  :8086 │   │  :8088 (P3)   │    │
          │(订单客服)│ │(商品咨询)│ │(认证)   │   │(图谱+协同过滤)│    │
          │(含知识库)│ │(含知识库)│ └────────┘   └───────────────┘    │
          │(含图谱) │ │(含图谱) │                                     │
          └─────────┘ └───────┘                                     │
                                                                     │
                    ┌───────────────────────────────────────────┐    │
                    │         Infrastructure                     │    │
                    │  Redis ─ Nacos ─ PostgreSQL                │    │
                    │  Milvus(etcd+MinIO) ─ Prometheus ─ Grafana │    │
                    │  Loki ─ Jaeger ─ Embedding(:8091)          │    │
                    └───────────────────────────────────────────┘────┘
```

### 请求流程

1. **前端请求** → Gateway (JWT 认证 + 限流)
2. **Gateway 转发** → Router (多 Agent 协作路由)
3. **Router 路由** → Consumer (会话管理 + 价值评估 + 记忆沉淀)
4. **Consumer 调度** → 对应 Agent(s)（通过 Nacos 服务发现）
5. **Agent 响应** → 通过 SSE 流式返回给前端
6. **价值评估** → 轮数≥3 或触发工具调用时触发 → `data/users/{userId}/memories/`（异步增量追加）

### 多 Agent 协作

复杂问题（如"推荐北京景点和川菜"）自动分解为子任务，顺序执行带共享上下文：

```
用户: "周末去北京玩，推荐景点和川菜馆"
  │
  Router.executeCollaborative()
  ├─ TaskPlannerService.plan()
  │   ├─ AgentDiscoveryService.getCachedAgents()  ← 动态发现
  │   └─ LLM 分解:
  │      t1|北京热门景点|location_weather
  │      t2|北京川菜餐厅|food_recommendation
  │
  ├─ parallelExecute() [带共享上下文]
  │   ├─ callAgent("location_weather", "北京景点")
  │   │   └─ 结果: 故宫、天坛、颐和园...
  │   │   └─ 存入 sharedContext
  │   │
  │   ├─ callAgent("food_recommendation",
  │   │     "北京川菜" + sharedContext)     ← 注入前序结果
  │   │   └─ 结果: 眉州东坡(近故宫)、...
  │   │   └─ 存入 sharedContext
  │   │
  │   └─ (若有更多维度以此类推)
  │
  ├─ ResultMerger.merge()
  │   └─ LLM 整合: "游览故宫后可去眉州东坡用餐..."
  │
  └─ 全部失败 → inlineFallback() ← 内联 ChatClient 终极兜底
```

**共享上下文**：子任务顺序执行，后执行的 Agent 能看到前面 Agent 的输出（如美食 Agent 推荐故宫附近的餐厅），无需 Agent 端改造。

---

## 技术栈

### 后端

| 分类 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 21+ (Temurin 21.0.6 LTS, `D:\Program Files\Java\jdk-21.0.6+7`) |
| 框架 | Spring Boot | 3.4.8 |
| AI 框架 | Spring AI（社区版） | 2.0.0 |
| 模型 | **DeepSeek V4-Flash**（通用/订单/商品 Agent 主通道）+ **Ollama 本地推理**（qwen2.5:7b Router 路由 + qwen2.5:3b 轻量任务） + BGE-large-zh ONNX (384d RAG) / BGE-small-zh ONNX (512d Cache) / bge-reranker-v2-m3 ONNX (重排序，实验性) | — |
| 注册/配置中心 | Nacos（元数据支持动态管理） | 2.3+ |
| 缓存 | Redis | 7.2+ |
| 数据库 | PostgreSQL (向量: Milvus) | 16+ (Milvus 2.5+) |
| 向量数据库 | Milvus（etcd+MinIO），IVF_FLAT + COSINE 索引 | 2.5.5 |
| 文档解析 | Apache PDFBox + Apache POI + JSoup | 3.0.3 / 5.3.0 / 1.18.3 |
| ORM | MyBatis-Plus | 3.5+ |
| 分词 | HanLP | 1.8.4 |
| SQL 解析 | jsqlparser | 4.9 |
| 推理引擎 | ONNX Runtime | 1.26+ |
| 构建 | Maven Wrapper | 3.9.6 |

### 前端

| 分类 | 技术 |
|------|------|
| 框架 | React 18 + TypeScript |
| UI 库 | TDesign |
| 通信 | WebSocket / SSE |
| 构建 | Vite |

### 监控

| 工具 | 用途 | 端口 |
|------|------|------|
| Prometheus | 指标收集 | 9090 |
| Grafana | 可视化仪表盘 | 3000 |
| Jaeger | 链路追踪 | 16686 |
| Loki | 日志聚合 | 3100 |
| Promtail | 日志采集 | — |
| Zipkin | 追踪兼容层 | 9411 |

### 推理引擎

系统采用 **双推理通道设计**，通过 Spring AI 原生 `ChatModel` 接口统一调用：

| 通道 | 模型 | 用途 | 调用方式 |
|------|------|------|---------|
| 🚀 **DeepSeek 云端** | deepseek-v4-flash | General/Order/Product Agent 主推理 | Spring AI 自动配置 `deepSeekChatModel` |
| 🏠 **Ollama 本地** | qwen2.5:7b | Router 路由决策、Consumer/Order 兜底降级 | Spring AI 自动配置 `ollamaChatModel` |
| ⚡ **轻量通道** | qwen2.5:3b | 对话摘要、偏好提取、缓存改写、关键词提取 | 委托模式 `lightChatModel` |

**2026-06-29 重构要点**：
- 全面启用 **Spring AI 原生集成**，移除自定义 HTTP 客户端
- 所有 DeepSeek/Ollama 调用走 Spring AI 自动配置，`ChatModel.stream()` 返回真实 SSE
- 轻量通道采用委托模式注入 auto-configured `OllamaChatModel`，避免重复构造复杂依赖

> 💡 DeepSeek 云端推理需要配置 `DEEPSEEK_API_KEY` 环境变量。

---

## 知识库召回管道

系统在 **Order** 和 **Product** 模块实现了基于 BGE 向量检索的知识库召回管道，用于从订单政策/商品信息知识库中检索相关内容增强 Agent 回答。

### 文档预处理管道

```
源文档 (PDF / Word / HTML / TXT)
    │
    ├── DocumentParseRouter（按扩展名自动路由）
    │   ├─ .pdf  → PdfDocumentParser（PDFBox 3.x，按页+段落解析）
    │   ├─ .docx → WordDocumentParser（Apache POI，按标题/段落/表格）
    │   ├─ .html → HtmlDocumentParser（JSoup，去噪+按h1~h6分节）
    │   └─ .txt  → TextFallbackParser（按空行分段）
    │
    ├── ParsedDocument（14 字段：content/sourceUrl/pageNumber/section/
    │   contentType/tenantId/version/effectiveAt/expireAt/contentHash/...）
    │
    ├── DocumentChunker（编排器）
    │   ├─ SemanticChunkStrategy（按语义边界分块：MD标题/章节编号）
    │   └─ RecursiveChunkStrategy（超长段落fallback，支持overlap）
    │
    └── KnowledgeDocument → 向量入库 + BM25索引
```

### 变更检测（2026-07-02 新增）

```
KnowledgeIngestionService.parseAndIngest(filePath, tenantId)
    │
    ├── Step 1: DocumentParseRouter.parse(filePath)
    │            → List<ParsedDocument> (含 contentHash)
    │
    ├── Step 1.5: ContentHashCache 变更检测
    │   ├── 按 baseDocId 分组（getBaseDocId 去除版本后缀）
    │   ├── HashUtil.aggregateHash(contents)
    │   │    对各段落做 normalize_text()（去时间戳/页脚页码/控制字符/连续空白）
    │   │    再拼接后计算 SHA-256
    │   └── ContentHashCache.needsReingest(baseDocId, newHash)
    │        ├── 首次摄入 → 标记为「已变更」，正常处理
    │        ├── hash 匹配 → 返回 IngestionResult.skipped（跳过全链路）
    │        └── hash 不匹配 → 标记为「已变更」，正常处理
    │
    ├── Steps 2~6: 注入租户 → 分块 → 入库 → reindex
    │
    └── Step 7: updateHashCache() 更新缓存哈希
```

**变更检测效果：**

| 场景 | 处理 | 节省流程 |
|:----|:-----|:---------|
| 首次摄入 | 全量处理，更新缓存 | — |
| 相同文档再次摄入 | hash 匹配 → 跳过 | 跳过解析/分块/嵌入/入库/reindex |
| 文档内容更新后摄入 | hash 不匹配 → 完整处理 | 正常流程 |
| 时间戳假变更（"更新时间：2026-06-29"） | normalize_text() 过滤后 hash 不变 → 跳过 | 避免误判全量重建 |

**工具类：**

- `HashUtil` — `normalizeText()` 去除假变更源（更新时间行、日期时间行、页脚页码、控制字符、连续空白），`sha256Hex()` 计算 SHA-256，`aggregateHash()` 多段落聚合
- `ContentHashCache` — ConcurrentHashMap 实现，支持 `hasChanged()` / `needsReingest()` / `remove()`，可替换为 Redis 实现

### 两阶段检索架构

```
用户查询
    │
    ├── QueryClassifier 分级
    │   ├─ DEEP(5路+Rerank) → 退款/合同/投诉等高价值
    │   ├─ STANDARD(3路+RRF) → 大多数业务查询
    │   └─ LIGHT(2路) → 问候/天气/计算等简单场景
    │
    ├── BGE 向量粗筛 (Top-50)
    │     使用 BGE-large-zh ONNX (384维) 计算语义相似度
    │     + ACL 检索前过滤（tenantId 隔离）
    │     + 时效性过滤（effectiveAt/expireAt）
    │
    ├── 精排融合
    │   ├─ cosSim × 版本优先级 × 时间衰减 (1 - BM25权重)
    │   └─ + BM25(k1=1.5, b=0.75) × BM25权重
    │   └─ 版本优先级：v1=1.0, v2=1.1, v3=1.2
    │
    ├── [可选] Cross-Encoder 重排序（实验性，默认关闭）
    │     bge-reranker-v2-m3 ONNX，SafeReranker 异常隔离
    │     配置 app.rag.reranker.enabled=true 启用
    │
    └── 质量门禁
        ├─ qualityScore ≥ 0.30 → 正常返回
        └─ qualityScore < 0.30 → INSUFFICIENT_EVIDENCE 拒答
```

### 版本控制

```
KnowledgeDocument.isSupersededBy(newerDoc):
    同 baseDocId（如 "ORD-REFUND-001"）且版本更高时标记被取代

reindex() 时自动执行 removeSuperseded()：
    按 baseDocId 分组 → 保留最高版 → 清除旧版数据
    无需手动管理，添加新版本文档后 reindex 即自动清理
```

### ACL 检索前过滤

```
所有 KnowledgeBase.search(query, topK, tenantId) 强制 ACL：

InMemoryKnowledgeBase:  search 循环中 tenantMatches() 过滤
PgVectorKnowledgeBase:  SQL WHERE 子句 buildAclClause()
MilvusKnowledgeBase:    search 参数 withExpr(buildAclExpr())

原则：权限过滤在检索前完成（返回前过滤），
     先召回高权限内容再让模型"不要说出来" = 自欺欺人
```

### 知识库内容

| 知识库 | 文档数 | 覆盖内容 |
|:------|:-----:|:---------|
| **订单知识库** (order_knowledge) | 10 | 退款政策、发货规则、支付说明、订单状态、优惠券规则、客服联系、售后服务、预约规则等 |
| **商品知识库** (product_knowledge) | 7 | 退换货政策、保修政策、价格保护、配送说明、库存规则等 |

### 存储方案

| 模式 | 说明 | 配置 |
|:----|:-----|:-----|
| **InMemory**（默认） | 内存索引 + BGE 向量 + BM25，启动时加载种子数据 | `app.milvus.enabled=false` |
| **Milvus**（可选） | Milvus 集群持久化(IVF_FLAT + COSINE)，跨实例共享 | `app.milvus.enabled=true`，需先 `docker compose -f monitoring/docker-compose-milvus.yml up -d` |

---

## Router 流水线

Router 的路由决策采用 **14 步流水线**（含 Step 0 多意图直通短路）：

```
用户请求
  │
  ├→ Step 0: 经验匹配 (ExperienceService) 
  │   BGE 向量匹配 TOOL/COMMON/REACT 经验，命中 TOOL 经验直接执行
  │   ⭐ 经验召回后经 ExperienceValidator 验证时效性/可靠度/新鲜度（警告随结果返回）
  │   ⭐ ExperiencePruner 每日 3:00 定时淘汰低质量经验（低 hitCount + 长空闲）
  │   ⭐⭐ 多意图检测：secondaryIntents 非空时直接并行调度多个 Agent
  │      虚拟线程并行调用 → CompletableFuture.allOf → 合并结果
  │      → 完全跳过 Step 1-5（无 LLM/缓存/上下文/改写）
  │
  ├→ Step 0.5: 关键词快车道 (KeywordFastRouteService) ⚡
  │   高频明确意图（退款/查订单/取消/问候/商品查询）跳过 LLM 分诊
  │   ⭐ 7.3μs/次，命中率 100%，加速比 ~68,000x
  │   ⭐ 跨 Agent 多意图检测：匹配到多条不同 Agent 规则时返回 null，走全管道
  │
  ├→ Step 1: 语义缓存 (SemanticCacheService)
  │   Tier 1 精确 → Tier 2 关键词 → Tier 3 BGE 向量; 命中直接返回
  │
  ├→ Step 2: L3 三路并行意图融合 (IntentFusionService) ⚡
  │   规则(KeywordFastRoute) ∥ 小模型(BGE余弦分类器) ∥ LLM(TaskAnalysis)
  │   ⭐ 规则≥0.9→直接输出(7μs)/小模型≥0.7→优先(10ms)/LLM兜底(200ms)
  │   ⭐ L5 意图漂移检测: 当前轮vs上轮用户问题BGE相似度<0.80触发
  │   融合结果经 IntentEvaluationService 规则层评测后处理
  │   实体归一化(日期/金额/地点/纠错) → 词槽分析(填充/缺失/冲突)
  │   → 澄清判断(追问生成) → 置信度阈值检查(<0.6触发澄清)
  │   存入 Redis 供下游消费
  │
  ├→ Step 3: 意图引导的查询改写 (IntentGuidedQueryRewriter) 
  │   根据意图类型选择改写策略：
  │   多意图→查询分解 / 模糊→查询扩展 / 精确→保留 / 对话→指代消解
  │   改写结果存入 Redis a2a:rewrite:{id}
  │
  ├→ Step 4: 构建上下文 + RAG 增强
  │   从 Redis 加载会话历史，可选 RAG 检索增强问题
  │   ⭐ 摘要回指：extractEntities() 提取六类实体(ORDER_ID/PRODUCT/AMOUNT/DATE/ACTION/DECISION)
  │   生成结构化 `【历史引用】` 文本，与 `【上下文】`+`【当前问题】` 三段式拼接
  │
  ├→ Step 5: 路由决策 (executeCollaborative)
  │   多 Agent DAG 协作: 图分解 → 拓扑并行执行 → Handoff 显式交接
  │   ⭐ ErrorType 错误分类：RETRYABLE→指数退避重试(1s→2s→4s, 最多3次)
  │   ⭐ successCriteria 验收：ReflectionService.checkCriteria() LLM 校验输出
  │   ⭐ NEED_REPLAN→TaskPlannerService.replan() 动态追加新节点
  │   ⭐ HandoffCommand(targetAgent, question, 累积上下文) 支持动态链式移交
  │   ⭐ 每个节点执行后可发起 Handoff，递归执行直到 COMPLETE/FAILED
  │   三级降级: Agent→inlineFallback→预设文案轮换
  │
  ├→ Step 5.5: 工具健康检查 (RoutingToolChecker) 🔑
  │   路由决策后检查目标 Agent 关键工具是否注册可用
  │   未通过时打印警告日志，配合 GET /tools/health 端点监控
  │
  └→ Step 6: 后处理 (finalizeRouting)
      ├─ Agent 执行事件发布 (AgentEventBus) 📋 → 持久化状态机事件链
      ├─ 反射器 (ReflectionService) — 纯规则五维评分
      ├─ LLM 质量评估 (QualityEvaluationService) — 四维语义评分
      ├─ 错误码恢复 (ErrorRecoveryService) — 表驱动重试
      ├─ 语义缓存写入 + 经验提取
      ├─ Bad Case 自动挖掘 (BadCaseMinerService) — 低置信度记录到 Redis
      └─ 完整决策写入 Redis (Consumer 通过 BLPOP 阻塞读取)
```

### 反射器（规则级，零 LLM 开销）

五维评分模型（总分 0.0 ~ 1.0，默认阈值 0.6）：

| 维度 | 权重 | 评分规则 | 说明 |
|------|:----:|----------|------|
| 长度检查 | 20% | ≥100字=1.0 / 50~99字=0.6 / 20~49字=0.3 / <20字=0.0 | 回复过短疑似异常 |
| 错误标记检测 | 25% | 不含=1.0 / 含❌⚠️ERROR等=0.0 | 明确的服务错误标记 |
| 关键词覆盖 | 25% | ≥50%=1.0 / 30%~50%=0.6 / <30%=0.3 | 问题中命名实体是否在回复出现 |
| Agent 健康状态 | 15% | 在线=1.0 / 离线=0.5 | Nacos 服务发现状态 |
| 意图匹配 | 15% | 匹配=1.0 / General=0.6 / 不匹配=0.3 | intentTag 与 Agent 对应关系 |

### 工作流程

```
Agent 返回结果
  │
  ├→ 反射器 (ReflectionService.evaluate())
  │   五维评分（纯规则，无 LLM 调用）
  │   ├─ score ≥ 0.6 → ✅ 通过 → 进入 LLM 质检
  │   └─ score < 0.6 → ❌ 不通过 → 换 fallback Agent 重试
  │
  ├→ LLM 质量评估 (QualityEvaluationService.evaluate())
  │   四维语义评分（仅反射器 0.50~0.80 边界区间触发）
  │   relevance/completeness/hallucination/helpfulness
  │   ├─ 通过 → ✅ 写入语义缓存 → 返回
  │   └─ 不通过 → ❌ 跳过缓存（防污染），不阻断用户响应
  │
  └→ 错误恢复 (ErrorRecoveryService)
      执行工具时自动解析 error_code JSON
      AgentErrorCode → shouldRetry() → 自动退避重试
```

### 配置项

```yaml
router:
  reflection:
    enabled: true        # 灰度开关
    threshold: 0.60      # 质量阈值（0.0~1.0）
    max-retry: 1         # 最大重试次数
  quality-evaluation:
    enabled: true
    threshold: 0.6
    reflection-lower-bound: 0.50   # 低于此值不走 LLM 质检
    reflection-upper-bound: 0.80   # 高于此值不走 LLM 质检
```

---

## @Tool 统一日志切面

系统通过 AOP 切面自动拦截全部 27 个 `@Tool` 方法，统一记录调用日志，无需在每个工具方法内手动写日志。

### 架构设计

```
Consumer (requestId 生成)
  │  ToolLogContext.setRequestId(requestId)
  │
  ├→ Router (HTTP 直调)
  │    │  AgentCallerService.enrichWithRequestId(instruction, requestId)
  │    │  → instruction 前缀注入: "[requestId:xxx]\n原始指令"
  │    │
  │    └→ Agent 服务
  │         │  ToolLogAspect (@Around)
  │         │  → 从 instruction 参数提取 requestId
  │         │  → MDC 设入，工具调用日志关联同一 requestId
  │         │
  │         └→ @Tool 方法执行
  │              │  ToolLogAspect (@Around)
  │              │  → MDC.get("toolRequestId") 获取 requestId
  │              │  → log.info("[Tool] method={} requestId={} params={} result={} duration={}ms")
  │              │
  │              └─ finally: ToolLogContext.clear()
```

### 日志格式

```
[Tool] method=calculate requestId=req-abc123 params={expression: "2+3*4"} result=14 duration=3ms
[Tool] method=getHotNews requestId=req-abc123 params={} result=[5条新闻] duration=222ms
```

- 参数截断至 300 字符，结果截断至 500 字符
- 异常时输出 `log.warn` 级别，包含 error 信息

### 新增监控指标（2026-07-02）

| 指标名 | 类型 | 标签 | 说明 |
|--------|------|------|------|
| `a2a_tool_error_type` | Counter | tool,error(retryable/fatal) | 按错误类型分类工具错误 |
| `a2a_tool_execution_latency` | Timer (p50/p95/p99) | tool | 含延迟分位数直方图 |
| `a2a_tool_execution_result` | Counter | status(retryable_failed/fatal_failed) | 错误分类计数器 |
| `a2a_tool_execution_latency_percentile` | Timer (p50/p95/p99) | — | 按 ErrorType 的延迟分位数 |

### 核心组件

| 组件 | 位置 | 说明 |
|------|------|------|
| `ToolLogContext` | `common/tool/` | ThreadLocal + MDC 持有 requestId |
| `ToolLogAspect` | `common/tool/` | `@Around` 拦截 @Tool，记录输入/输出/耗时 |
| `spring-boot-starter-aop` | `common/pom.xml` | AOP 依赖（optional） |

---

## 语义缓存

系统在 Router 模块实现了三层语义缓存，全部本地执行无需外部 API 调用。Tier 3 使用 **BGE-small-zh ONNX(512d)** 本地模型，零网络依赖。

### 缓存架构

```
getCachedDecision(question)
├── Tier 1: 精确匹配 MD5(question)         → ~1ms    Redis GET
│   └── 命中后如果 reply 不存在，兜底 keyword reply
│
├── Tier 2: 关键词哈希(分词→排序→MD5)     → ~5ms    Redis GET（无需 LLM）
│   └── "上海天气怎么样" 和 "上海天气如何" → 相同 hash
│   └── 回复缓存首次执行后立即保存
│
└── Tier 3: BGE ONNX 向量匹配(余弦≥0.70)   → ~5ms    本地 ONNX Runtime
    └── BGE-small-zh-v1.5 ONNX (512d)，BERT 标准 tokenization
    └── 自动降级 TF 向量（模型文件缺失时）
    └── VectorCacheStore 内存索引，最大 10k 条
    └── 阈值 0.70 覆盖前缀扩展（"北京天气"→"北京天气冷不冷"）
```

### 回复缓存策略

系统在 `saveReply()` 时按以下顺序判断是否跳过回复缓存（路由决策始终缓存）：

```
saveReply() 时
├── 会话复述类提问?（"我刚刚问了什么"、"再说一遍"）
│   └── ✅ 是 → 跳过（回答仅对当前会话有效）
│
├── 管理员工具执行标记存在? (Redis admin:tool:called:latest)
│   └── ✅ 是 → 跳过（Agent 端 @AdminOnly 工具触发后写入）
│
├── Agent 声明 cache-ttl-seconds < 3600 ?
│   └── ✅ 是 → 跳过（天气类 ttl=1200，每次拿最新数据）
│
├── 意图被问到 ≥2 次?（即高频意图）
│   └── ❌ 否 → 跳过（低频问题缓存也不会命中）
│
└── 通过 → 缓存回复（关键词级 + 意图级）
```

| 缓存类型 | Key | 写入时机 | TTL |
|---------|-----|---------|:---:|
| 路由决策 | `a2a:route:semantic:{md5(intentTag)}` | 每次路由后 | 24h |
| 精确映射 | `a2a:route:exact:{md5(question)}` | 每次路由后 | 24h |
| 关键词路由 | `a2a:route:keyword:{md5(keywords)}` | 每次路由后 | 24h |
| **关键词回复** | `a2a:route:keyword:reply:{md5(keywords)}` | **Agent 执行后（TTL≥1h 时）** | 动态 |
| 意图回复 | `a2a:route:reply:{md5(intentTag)}` | 被问到 ≥2 次后（TTL≥1h 时） | 动态 |

### 缓存版本一致性

Router 和 Consumer 之间的缓存通过 `CacheVersionManager` 保持一致性：

```
Router (经验变更时)
  ExperienceService.saveExperience()
    └→ cacheVersionManager.incrementVersion()
         └→ Redis: a2a:cache:version = 3

Consumer (读取缓存前)
  VectorSearchCacheService.searchWithCache()
    └→ 缓存值格式: "3|answer text"
    └→ isCacheVersionValid("3|...", 当前版本)
         ├→ 版本匹配 → 返回缓存
         └→ 版本不匹配 → 跳过缓存，重新生成
```

- 旧版缓存（无 `{version}|` 前缀）默认通过，兼容过渡期
- 本地 5 秒版本缓存，避免每次查询都读 Redis

### 动态 TTL（按 Agent 类型 + 问题内容）

| Agent | 场景 | TTL | 说明 |
|:-----|------|:---:|------|
| location_weather | 纯天气查询 | **20min** → 低于 1h 阈值，回复不缓存 | 气温/降水实时更新 |
| location_weather | 景点/推荐/去哪 | **12h** | 景区信息几乎不变 |
| food_recommendation | 今日推荐 | **2h** | 每日特价更新 |
| food_recommendation | 一般美食查询 | **12h** | 餐厅信息稳定 |
| general_chat / builtin | 闲聊/问答 | **2h** | 事实性回答稳定 |

### 回复前缀个性化

根据提问者是否为同一用户，自动选择不同前缀：

| hitCount | 同用户 | 不同用户 |
|:--------:|--------|----------|
| == 2 | `再帮你查一次，结果和之前一样～` | `查询结果如下：` |
| ≥ 3 | `（以下是我之前查到的信息）` | `（以下是根据历史查询获取的信息）` |
| > 6h | `📅 根据6小时前查询的信息` | `📅 以下信息来源于6小时前的数据` |

### 性能效果

| 场景 | 典型耗时 | Agent 调用 |
|:----|:-------:|:---------:|
| 冷启动（无缓存） | 5-18s | ✅ 执行 |
| 路由缓存命中（Tier 1/2 无回复） | 3-15s | ✅ 执行 |
| **全缓存命中（Tier 1/2/3）** | **1-5ms** | **❌ 跳过** |
| BGE 向量匹配命中（前缀扩展兜底） | **5ms** | ❌ 跳过 |

每意图仅需 **1-2 次 Agent 执行**（改前需 5 次），缓存命中时延迟从 5-18s 降至 **1-5ms**。

---

## 服务说明

| 服务 | 端口 | 职责 |
|------|------|------|
| **Gateway** | 8081 | API 统一入口，JWT 认证，Redis 限流，负载均衡 |
| **Consumer** | 8082 | SSE 流式对话入口，**三层请求排队流控**(L1全局/L2会话级/L3优先级)，上下文管理，用户画像**时间衰减**，对话记忆沉淀，SSE **断线续传**(Sorted Set O(log n) + EventSource Last-Event-ID)，**Agent 事件总线异步消费**，提供 `/api/data/query` 数据查询独立端点 |
| **Router** | 8083 | 多 Agent 协作路由，**三层语义缓存**，任务分析(意图/置信度/实体/约束/风险/工具评分)，**评测后处理**(实体归一/词槽/澄清/置信度阈值)，**意图查询改写**，**意图向量化检索**(BGE Top-K 动态注入)，DAG 图分解→拓扑并行执行(**指数退避重试**+**验收标准检查**+**重规划触发**)→**Handoff 显式交接**(同步HTTP/异步EventBus可选)，**二阶段质量评估**(反射器+LLM质检)，**标准错误码表驱动恢复**，**Agent 级工具自动重试**，**经验验证+淘汰**(定时清理低质量经验)+**BGE 语义合并**(cosine≥0.85自动合并)，**摘要回指**(六类实体提取+结构化历史引用)，Nacos 服务发现 |
| **Embedding Service** | 8091 | 独立 BGE ONNX 嵌入服务（1024维），供 Router 语义缓存、知识库检索和 ExperienceService 经验匹配 |
| **Order** | 8085 | 订单查询(Text-to-SQL)，退款处理，物流跟踪，优惠券查询，**BGE 知识库检索 + bge-reranker 重排序**，**独立记忆**(Markdown 文件存储，savePreference/recallMemories) |
| **Product** | 8084 | 商品查询，库存检查，价格查询，**BGE 知识库检索**，**独立记忆**(商品偏好/价格区间/品牌偏好)，**P3 商品图谱**(15节点/36边，5种关系类型，Path 5图检索集成) |
| **User** | 8086 | 用户注册登录，JWT Token 签发，角色管理 |
| **General** | 8087 | 闲聊问答，新闻热点，单位转换(温度/长度/重量/货币)，**沙箱保护的多步脚本执行**(ScriptSandbox 三层防御: 关键字黑名单/资源限制/超时熔断)，**独立记忆**(回复风格/常用单位/兴趣话题)，支持风格切换 |
| **Recommend** | **8088** | **🎯 P3 跨模块推荐服务**。三策略推荐引擎：图谱推荐(ProductGraphService)→协同过滤(OrderGraphService 用户购买历史)→热门兜底。Feign 调用 Product+Order REST API |

---

## 环境要求

### 开发环境

- **操作系统**：Windows 10/11 或 macOS / Linux
- **Java**：JDK 21+（推荐 Eclipse Temurin 21.0.6+，`D:\Program Files\Java\jdk-21.0.6+7`）
- **Maven**：3.9+（可选，使用 `bash mvn21.sh` 自动调用系统 Maven）
- **Node.js**：18+（前端构建）
- **Docker**：24+（基础设施服务）
- **Git**：2.x（版本管理）

### 一键安装脚本

```powershell
# 检查依赖
bash mvn21.sh -v        # ⭐ 推荐：绕过 Git Bash 的 Maven 路径兼容问题
.\mvnw.cmd --version    # 备用：Windows CMD 下使用（需先设置 JAVA_HOME）
java -version
node -v
docker --version
```

> ⚠️ **重要**：本项目需要 JDK 21+。在 Git Bash 下 `./mvnw` 因 MSYS 路径兼容问题无法正常工作，请使用 `bash mvn21.sh <goals>` 替代。

---

## 快速开始

### 1. 启动基础设施

```powershell
# 启动 Redis + Nacos + Zipkin
cd D:\workspace\SmartAssistant
docker-compose up -d

# 可选：启动 Milvus 向量数据库（启用 app.milvus.enabled=true 时需要）
docker compose -f monitoring/docker-compose-milvus.yml up -d

# 验证
docker ps --format "table {{.Names}}\t{{.Status}}"
```

### 1b. 启动 Ollama 本地推理引擎

> ⚠️ **必须操作**：所有 Agent 服务依赖本地 Ollama 模型，启动服务前请确保：

```powershell
# 1. 安装 Ollama（仅首次）
# Windows: 从 https://ollama.com/download 下载安装
# Linux: curl -fsSL https://ollama.com/install.sh | sh

# 2. 下载模型（仅首次）
ollama pull qwen2.5:7b    # 主推理模型

# 3. 启动 Ollama 服务
ollama serve

# 4. 验证模型可用
ollama list
```

### 2. 配置环境变量

```powershell
# 从模板创建配置文件
cd D:\workspace\SmartAssistant
copy .env.example .env
```

关键变量说明：

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `DEEPSEEK_API_KEY` | DeepSeek API 密钥（General/Order/Product 需要） | — |
| `POSTGRES_PASSWORD` | PostgreSQL 密码 | `postgres123` |
| `REDIS_PASSWORD` | Redis 密码 | `redis123` |
| `NACOS_PASSWORD` | Nacos 密码 | `nacos123` |
| `JWT_SECRET` | JWT 签名密钥 | 建议自行生成 |
| `app.data.dir` | 数据存储根目录 | `data` |

> 💡 **混合推理架构**：General/Order/Product Agent 使用 DeepSeek 云端推理（需 `DEEPSEEK_API_KEY`），Router/Consumer 使用 Ollama 本地推理。
> 
> 轻量模型（qwen2.5:3b）通过 Spring AI 委托模式注入，无需额外配置。

### 3. 构建项目

> ⚠️ Git Bash 下 `./mvnw` 因 MSYS 路径兼容问题不可用，请使用 `bash mvn21.sh`。
> PowerShell/cmd 下可使用 `mvnw.cmd`，但需先执行 `set JAVA_HOME=D:\Program Files\Java\jdk-21.0.6+7`。

```powershell
cd D:\workspace\SmartAssistant

# ⭐ 推荐：使用 mvn21.sh 构建（兼容 Git Bash）
bash mvn21.sh install -pl smart-assistant-common -DskipTests
bash mvn21.sh compile -DskipTests

# 全量打包
bash mvn21.sh package -DskipTests -Dmaven.test.skip=true

# 前端（React + TypeScript + TDesign）
cd frontend && npm install && cd ..
```

### 4. 启动服务

#### 方式一：IDE 开发（IntelliJ IDEA 推荐）

1. 打开项目根目录
2. 设置运行配置的环境变量（指向 `.env` 中的值）
3. **必须先清除 `SERVER__PORT` 环境变量**（双下划线会覆盖 `server.port`）：`Remove-Item Env:SERVER__PORT`
4. 按依赖顺序启动：
   - 先启动基础设施：Redis、Nacos（`docker-compose up -d`）
   - 启动 **Embedding Service**（8091）：需先让 BGE ONNX 模型就绪
   - 启动 **Gateway**（8081）→ **User**（8086）→ **Consumer**（8082）→ **Router**（8083）→ **Order**（8085）→ **Product**（8084）→ **General**（8087）
   - 无严格顺序要求，但 Router 依赖 Nacos，Consumer 依赖 Redis

```powershell
# 批量启动（需先修改各服务的端口配置）
cd D:\workspace\SmartAssistant
Remove-Item Env:SERVER__PORT

# 逐个启动（推荐在 IDE 中启动，方便调试）
.\mvnw.cmd spring-boot:run -pl smart-assistant-gateway
```

#### 方式二：前端

```powershell
cd frontend && npm install && npm run dev
```

---

## 详细部署流程

### 数据库初始化

```powershell
# 创建数据库
psql -h 127.0.0.1 -U postgres -c "CREATE DATABASE a2a_system;"

# 安装 pgvector 扩展
psql -h 127.0.0.1 -U postgres -d a2a_system -c "CREATE EXTENSION IF NOT EXISTS vector;"

# 执行初始化脚本
$env:PGPASSWORD='postgres123'; & "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h 127.0.0.1 -U postgres -d a2a_system -f "docs/database/schema.sql"
$env:PGPASSWORD='postgres123'; & "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h 127.0.0.1 -U postgres -d a2a_system -f "docs/database/seed_data.sql"

# ⚠️ chat_messages 相关表已废弃，由 data/users/{userId}/memories/ 替代
# 如需删除旧表，执行：docs/database/cleanup_v20260508.sql
```

### 启用 pg_stat_statements（可选，SQL 性能监控）

```sql
-- 1. 修改 postgresql.conf
-- shared_preload_libraries = 'pg_stat_statements'

-- 2. 重启 PostgreSQL 后执行
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
```

### 监控栈部署

```powershell
cd monitoring
docker-compose -f docker-compose.yml up -d

# ⭐ 若启用 Milvus 向量数据库，还需启动 Milvus 集群
docker compose -f docker-compose-milvus.yml up -d
```

监控组件：

| 组件 | 访问地址 | 说明 |
|------|---------|------|
| Grafana | http://localhost:3000 | 仪表盘（默认 admin/admin） |
| Prometheus | http://localhost:9090 | 指标查询 |
| Jaeger | http://localhost:16686 | 链路追踪 |
| Loki | http://localhost:3100 | 日志查询 |
| Zipkin | http://localhost:9411 | 追踪兼容层 |

### Docker 容器化部署

项目提供完整的 Docker 容器化方案，支持一键构建部署全部 7 个服务：

```powershell
# 1. 从项目根目录构建
cd D:\workspace\SmartAssistant

# 2. 一键构建并启动所有服务
docker compose -f docker-compose.deploy.yml build
docker compose -f docker-compose.deploy.yml up -d

# 3. 验证
docker compose -f docker-compose.deploy.yml ps
```

各服务独立构建（适用于单独更新某个服务）：

```powershell
docker compose -f docker-compose.deploy.yml build gateway
docker compose -f docker-compose.deploy.yml up -d gateway
```

部署文件说明：

| 文件 | 用途 |
|------|------|
| `Dockerfile` | 多阶段构建（打包 → JRE 运行） |
| `docker-compose.deploy.yml` | 编排全部 7 个服务 + 基础设施 |
| `.dockerignore` | 排除本地配置和构建缓存 |

---

## 配置说明

### 模型文件

系统使用以下模型文件（不随代码提交，需单独放置）：

| 模型 | 用途 | 路径 | 大小 |
|------|------|------|:----:|
| BGE-small-zh-v1.5 | Router Cache T3 语义匹配 | `models/bge-small-zh-v1.5.onnx` | 95 MB |
| BGE-large-zh-v1.5 | Product/Order RAG 检索（384维） | `models/bge-large-zh-v1.5.onnx` | 1.2 GB (FP32) |
| **bge-reranker-v2-m3** | Cross-Encoder 重排序（实验性，默认关闭） | `models/bge-reranker-v2-m3.onnx` | 192 KB + 2.2 GB 权重 |
| **deepseek-r1:7b** (Ollama) | **主推理模型** | Ollama 管理 | 4.7 GB |
| **qwen2.5:7b** (Ollama) | **备选推理模型** | Ollama 管理 | 4.7 GB |

**推理引擎**：所有服务默认使用 Ollama 本地模型（deepseek-r1:7b），通过 Spring AI @Primary ChatModel 注入。无云端 API 依赖。

模型路径通过 `bge.model.path` 配置（支持环境变量 `BGE_MODEL_PATH` 覆盖），首次加载约 4-5 秒，纯本地推理 30-50ms/次。

### 微调管道

项目根目录 `training/` 包含完整的 QLoRA 微调工具集，用于对本地 7B 模型进行参数高效微调：

```
training/
├── app.py               # Streamlit 控制面板
├── finetune_lora.py     # QLoRA 微调主脚本
├── prepare_data.py      # 数据准备与生成
├── export_gguf.py       # 导出 GGUF + 注册 Ollama
├── test_model.py        # 快速测试
└── sample_data/         # 示例数据
```

使用详见 `training/README.md`。

### 配置层级（优先级从高到低）

1. **命令行参数**：`--server.port=8082`
2. **OS 环境变量**：`OLLAMA_BASE_URL=http://server:11434`（默认 localhost:11434）
3. **`.env` 文件**：项目根目录（自动加载）
4. **`application.yml`**：各服务的配置文件
5. **默认值**：`${VAR:defaultValue}` 语法

> 💡 **混合推理架构**：General/Order/Product 使用 DeepSeek 云端（需配置 `DEEPSEEK_API_KEY`），Router/Consumer 使用 Ollama 本地。

### 优雅关闭

所有服务已配置优雅关闭（2026-05-11），确保服务停止时处理中的请求完成后再退出：

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: "30s"
```

无需额外操作，`kill` 或 `Ctrl+C` 时自动生效。

### 关键配置目录

```
smart-assistant-{service}/src/main/resources/
├── application.yml           # 服务配置
├── logback-spring.xml        # 日志配置
├── prompts/                  # 系统提示词（外部化，改提示词无需重新编译）
│   ├── travel-system-prompt.txt
│   ├── food-system-prompt.txt
│   └── general-system-prompt.txt
├── skills/
│   └── skills.yml            # ⭐ P4 可配置技能仓库（9 个技能）
└── config/
    └── mcp-table-whitelist.yml  # MCP 表访问白名单
```

### P4 调度配置

```yaml
# application.yml — 默认关闭，启用后 Router 支持异步任务队列
scheduler:
  enabled: false                # 设为 true 启用
  worker-count: 4               # Worker 线程数
  poll-timeout: 10              # BRPOP 轮询超时（秒）
```

### 系统提示词外部化

各 Agent 服务的系统提示词已从 Java 代码中提取到独立的资源文件，修改无需重新编译：

```
smart-assistant-{service}/src/main/resources/prompts/
├── order-system-prompt.txt        # 订单客服助手
├── product-system-prompt.txt      # 商品咨询助手
└── general-system-prompt.txt      # 通用对话助手
```

加载方式：`@Value("classpath:prompts/{service}-system-prompt.txt")`，IO 异常时自动降级为默认提示词，不影响服务启动。

### Service 包结构

```
smart-assistant-router/.../service/
├── core/          RouterService, SmartRoutingService, ReflectionService(含 checkCriteria 验收检查)
├── agent/         AgentCallerService, AgentDiscoveryService, AgentHealthChecker, AgentVersionNegotiator
├── cache/         SemanticRouteCacheService, RoutingDecisionStorageService
├── infrastructure/ DistributedTracingService
├── extraction/    KeywordExtractionService
├── rag/           RouterRagService(含摘要回指: extractEntities+generateBackReferences)
├── evaluation/    EntityNormalizer, SlotStateMachine, ClarificationService,
│                  IntentEvaluationService, IntentGuidedQueryRewriter 🆕
├── monitoring/    RouterMetricsCollector, NewMetricsCollector(含 ErrorType+延迟分位数)
├── experience/    ExperienceService(含 BGE 语义合并 findSimilarByBGE)
├── tool/          RoutingToolChecker
├── skill/         SkillRepository, SkillDefinition
└── taskanalysis/  TaskAnalysisService

smart-assistant-food/.../service/
├── core/          ABTestService, HybridRecommendationService, ReviewEmbeddingInitializer
├── search/        RestaurantReviewSearchService
├── infrastructure/ DistributedTracingService
├── agent/         StreamingFoodAgentService
└── monitoring/    FoodMetricsCollector

smart-assistant-travel/.../service/
├── rag/           TravelRagService, TravelNoteService, TravelNoteMatchService, TravelNoteRankingService,
│                  SemanticChunker, EmbeddingService, RecallService, AttractionVectorService
├── data/          DatabaseAttractionService, AttractionDataImportService, AmapPoiSyncService,
│                  DataQualityValidator
├── infrastructure/ DistributedTracingService
├── agent/         McpAgentService, StreamingTravelAgentService
└── monitoring/    TravelMetricsCollector
```

### 查看日志

所有服务的日志统一输出到 `logs/` 目录，格式为 `{spring.application.name}.log`：

```powershell
# 应用日志（纯净 Spring Boot 输出，推荐优先查看）
type logs\router-service.log -Tail 50
type logs\order-service.log -Tail 50
type logs\product-service.log -Tail 50
type logs\general-agent-service.log -Tail 50
type logs\consumer-service.log -Tail 50
type logs\api-gateway.log -Tail 50
type logs\user-service.log -Tail 50

# 标准输出日志（含启动横幅等）
type logs\Router-stdout.log -Tail 30
type logs\Order-stdout.log -Tail 30
type logs\Product-stdout.log -Tail 30
```

### ⚠️ 密码配置强制要求

以下密码**必须通过环境变量设置**，未设置时服务启动即报错：

| 变量 | 说明 |
|------|------|
| `JWT_SECRET` | JWT 签名密钥 |
| `POSTGRES_PASSWORD` | PostgreSQL 密码（默认 postgres123） |
| `REDIS_PASSWORD` | Redis 密码（默认 redis123） |
| `NACOS_PASSWORD` | Nacos 密码（默认 nacos123） |

### 用户数据存储

所有用户数据存储在 `data/users/{userId}/` 目录下，不再依赖数据库：

```
data/users/{userId}/
├── preferences.json            # 用户偏好（权重、意图分布）
├── order-memory.md             # Order Agent 记忆（行程偏好、座位偏好）
├── product-memory.md           # Product Agent 记忆（品类偏好、价格区间）
├── general-memory.md           # General Agent 记忆（回复风格、常用单位）
└── memories/
    ├── 2026-05-08_session_abc.md   # 增量追加记忆文件
    └── 2026-05-08_session_def.md
```

**Agent 独立记忆**：每个 Agent 拥有用户粒度的键值记忆文件（Markdown 格式），记录可复用的用户偏好和习惯。

```markdown
# Order Agent 用户偏好

- preferWindowSeat: 靠窗
- frequentRoute: 北京→上海
- preferPaymentMethod: 微信支付
```

Agent 通过 `savePreference(userId, key, value)` 写入，`recallMemories(userId)` 读取。
系统在每次处理用户问题前自动将记忆注入 prompt 上下文，提供个性化回复。
记忆文件在首次写入时自动创建，无需手动初始化。

**对话摘要持久化（方案 A）**：SmartReActAgent 每次 Context 压缩后，自动将 9 段式摘要嵌入为 BGE 向量并存入 Milvus `conversation_summaries` Collection（IVF_FLAT + COSINE 索引）。支持按用户语义检索历史摘要。通过 `withSummaryStore()` 注入，不可用时静默降级。

---记忆文件采用 **session 优先的增量追加**模式。同 session 始终追加到同一文件（跨天不换文件），文件名为首次创建的日期：

```yaml
---
created_at: 1746680000000
session: session_abc
turn_range: 3-5
entries: 3
---

> Turn 3 | narrative | intent: 景点查询

用户咨询了景点信息，系统推荐了故宫和天坛...

---

> Turn 4 | raw | intent: 美食推荐

用户：有川菜推荐吗？
助手：眉州东坡、辣婆婆...

---

> Turn 5 | narrative | intent: 旅游规划

用户继续咨询三日游行程安排，系统制定了详细规划...
```

每个条目通过 `> Turn {n} | {format} | intent: {intentTag}` 标记轮次和格式：
- **`narrative`** — LLM 第三人称叙事摘要（轮数≥3 且内容≥1000 字符时触发）
- **`raw`** — 原文保存（内容不足摘要阈值）
- 超 8000 字符时内容被安全截断，截断部分以 `---` 分隔直接原文追加

### 全局纠错记录

Agent 的用户修正记录存储在 `data/corrections/` 目录，所有用户共享，用于避免重复错误：

```
data/
├── users/{userId}/memories/       # 用户私人记忆（增量追加）
└── corrections/
    ├── travel.md                   # 出行修正记录（全局共享）
    ├── food.md                     # 美食修正记录
    └── general.md                  # 通用修正记录
```

修正记录格式：

```markdown
# Travel Agent 用户修正记录

## 2026-05-11

> 主题: 故宫开放时间
> 错误: 故宫8:00开门
> 正确: 故宫冬季8:30开门，夏季8:00开门
> 来源: userId=123
```

各 Agent 的 system prompt 强制要求：回答事实性问题前先调用 `queryCorrections(topic)` 工具，有匹配修正时以修正信息为准。



---

## 请求排队

系统基于压测数据实现了三层请求排队流控机制，在 LLM 并发槽位占满时自动排队，通过 SSE 实时通知用户排队状态。

### 三层流控

| 层级 | 机制 | 配置 | 效果 |
|:----|:-----|:-----|:-----|
| **L1** | Semaphore 全局并发控制（公平模式） | `chat.queue.max-concurrent` (默认 5) | 限制整体 LLM 并发数 |
| **L2** | 会话级并发控制（ConcurrentHashMap + AtomicInteger） | `queue.session-max-concurrency` (默认 1) | 同一 sessionId 最多 1 个请求在处理，防刷屏 |
| **L3** | PriorityBlockingQueue 优先级调度 | `queue.priority-enabled` (默认 false) | VIP 用户请求优先获取槽位，priority 越低越优先 |

### 工作流程

```
用户发送消息
  │
  ├→ L2: 检查会话并发数(已占用? → 排队)
  │
  ├→ L1: [有槽位] event: processing → 正常 SSE 流 → event: done
  │
  └→ [无槽位] event: queued {position: 3, estimatedWaitMs: 15000}
              │
              ├→ L3: PriorityBlockingQueue 自动排序
              ├→ Semaphore.tryAcquire(60s 超时) 阻塞等待
              │
              ├→ [获取到槽位] event: processing → 正常 SSE 流 → event: done
              └→ [超时] event: timeout → 连接关闭

处理完成后自动释放 L1 槽位 + L2 会话槽位
```

### SSE 事件类型

| 事件 | 说明 | 数据 |
|------|------|------|
| `queued` | 进入排队 | `{position, estimatedWaitMs}` |
| `processing` | 槽位分配，开始处理 | `{}` |
| `timeout` | 排队超时 | `{content: "排队超时，请稍后重试"}` |
| `waiting` | 等待路由决策 | `{content: "正在分析意图..."}` |
| `thinking` | AI 思考过程 | `{type: "thinking", content: "..."}` |
| `tool_call` | 工具调用 | `{type: "tool_call", name, input}` |
| `tool_result` | 工具执行结果 | `{type: "tool_result", content}` |
| `response` | 最终回复 | `{type: "response", content}` |
| `done` | 完成信号 | `{type: "done"}` |

### 配置项

在 `smart-assistant-consumer/application.yml` 中：

```yaml
chat:
  queue:
    max-concurrent: 5          # 最大并发 LLM 请求数（基于压测数据）
    max-queue-size: 50         # 最大排队长度
    queue-timeout-ms: 60000    # 排队等待超时（毫秒）
```

### 前端表现

排队中时，用户消息气泡下方显示：

```
⏳ 排队中，前面还有 3 人，预计等待 15 秒
```

槽位就绪后自动开始处理，用户无感知。

---

## API 文档

### 认证接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/assistant/api/auth/login` | 用户登录，返回 JWT Token |
| POST | `/assistant/api/auth/register` | 用户注册 |
| GET | `/assistant/api/auth/me` | 获取当前用户信息 |

### 对话接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/assistant/api/math/chat` | 发送消息（非流式，通过 Router 路由到对应 Agent） |
| POST | `/assistant/api/data/query` | ⭐ 数据查询（仅 ADMIN，独立端点，不混入对话流） |
| WebSocket | `/assistant/ws/conversation` | WebSocket 实时对话 |

### 用户反馈接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/feedback` | 提交用户对 Agent 回复的评价（like/dislike）。请求体：`{userId, sessionId, question, response, rating, reason}` |

### 通用助手工具（General Agent）

| 工具 | 说明 | 依赖 |
|------|------|------|
| `calculate(expression)` | 数学计算 | — |
| `convertTemperature/Length/Weight` | 单位转换 | — |
| `getHotNews()` | 网络热点新闻 | — |
| `searchWeb(query)` | 联网搜索 | — |
| `convertCurrency(value, from, to)` | 货币汇率转换 | 实时汇率 API |
| `executeScript(script)` | **沙箱保护**的多步计算脚本执行（关键字黑名单/资源限制/超时熔断） | — |
| `queryWeather(city)` | 天气查询 | — |
| `savePreference(userId, key, value)` | 保存用户偏好（Order/Product/General 三 Agent 均有） | AgentMemoryService |
| `recallMemories(userId)` | 获取用户保存的所有偏好记忆 | AgentMemoryService |

### Agent 控制接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/order/agent/process` | Order Agent 直调（支持 userId 可选，自动注入记忆和后台提取偏好） |
| POST | `/product/chat/sync` | Product Agent 同步对话 |
| GET | `/product/stream/chat` | Product Agent SSE 流式对话 |

### 路由接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/assistant/api/router/route` | 智能路由（意图识别 + Agent 调度） |
| GET | `/assistant/api/router/tools/health` | 工具健康检查（查看各 Agent 工具注册状态） |
| GET | `/assistant/api/router/events/{requestId}` | Agent 执行轨迹查询（状态机事件链） |

### 推荐接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/recommend` | 🎯 P3 跨模块推荐。请求体：`{userId, productCode, maxResults}`。支持图谱推荐+协同过滤+热门兜底三策略 |
| GET | `/api/recommend/health` | 推荐服务健康检查 |

### 图谱接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/product/list` | 全量商品列表（含品类/品牌） |
| GET | `/api/product/{code}/recommend` | 商品图谱关联推荐 |
| GET | `/api/order/user/{userId}/products` | 用户购买历史（商品编码列表） |
| GET | `/api/order/user/{userId}/orders` | 用户订单摘要 |

### 智能纠错工具（所有 Agent）

| 工具 | 适用 Agent | 说明 |
|------|-----------|------|
| `queryCorrections(topic)` | Travel / Food / General | 查询历史修正记录。Agent 在回答事实性问题前自动调用，检查是否有用户反馈过的修正信息并优先采用 |

### 健康检查

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/actuator/health` | 服务健康状态 |
| GET | `/actuator/prometheus` | Prometheus 指标 |
| GET | `/actuator/info` | 服务信息 |

### 离线评测

| 工具 | 路径 | 说明 |
|------|------|------|
| 黄金测试集 | `docs/eval/sample-test-set.json` | 14 个测试用例覆盖 Order/Product/General 三个 Agent。运行：`java com.example.smartassistant.common.eval.GoldenTestRunner docs/eval/sample-test-set.json` |
| 对抗式测试集 | `docs/eval/adversarial-test-set.json` | 22 个对抗式用例覆盖 5 类场景：无答案问题(3)、Prompt 注入(3)、权限越界(2)、同义改写鲁棒性(5)、否定/错别字/多意图(6)。预期含 INSUFFICIENT_EVIDENCE 拒答检查 |

---

## 常见问题

### Q: 服务启动绑定到异常端口（如 63807）

检查是否设置了 `SERVER__PORT` 环境变量（注意是双下划线）。该变量会覆盖 `server.port` 配置，需要在 shell 中清除：

```powershell
# PowerShell
Remove-Item Env:SERVER__PORT

# CMD
set SERVER__PORT=
```

检查 `.env` 文件是否存在且包含正确的 `DEEPSEEK_API_KEY`。如果以 IDE 启动，需在运行配置中设置环境变量。


### Q: 中文请求返回"一串问号"

Windows 终端（PowerShell/cmd）默认使用 GBK 编码发送 JSON，服务端只接收 UTF-8。PowerShell 中需强制 UTF-8 编码：

```powershell
$body = @{ message='你的中文问题' } | ConvertTo-Json
$utf8Bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
Invoke-RestMethod -Uri 'http://localhost:8081/assistant/api/math/chat' -Method Post -Body $utf8Bytes -ContentType 'application/json; charset=utf-8' -Headers $headers
```

cmd 中先执行 `chcp 65001` 切换到 UTF-8 代码页。


### Q: 编译报错"编码 UTF-8 的不可映射字符"

这是由于使用 PowerShell 的 `Set-Content` 命令操作含中文的 Java 文件时，默认使用了系统 ANSI 编码（GBK），导致中文字符损坏。

**修复方法**：使用 `git checkout -- <file>` 恢复原始文件，然后使用以下命令之一操作：
- Bash: `sed -i`（正确处理 UTF-8）
- PowerShell: `[System.IO.File]::WriteAllBytes($path, [System.Text.Encoding]::UTF8.GetBytes($content))`

### Q: Consumer 与 Router 边界不清如何处理？

2026-05-11 已重构：Consumer 不再将 JSON Prompt 塞入 `question` 字段，改为通过独立字段传递元数据（`userProfile`、`intentTag`、`requestId`、`sessionId`）。Router 不再需要 `extractRequestId()`/`extractThreadId()` 反解析。数据查询功能拆分为独立 `DataQueryController`。

### Q: 模型切换

系统支持**双推理通道**：
- **DeepSeek 云端**（通用/订单/商品 Agent）：修改各服务 `application.yml` 中 `spring.ai.deepseek.chat.options.model`
- **Ollama 本地**（Router/Consumer/轻量通道）：修改 `spring.ai.ollama.chat.options.model`

各通道独立配置，互不影响。

### Q: DeepSeek API Key 配置

General/Order/Product 服务通过 Spring AI 自动配置读取 `spring.ai.deepseek.api-key`，可以通过环境变量 `DEEPSEEK_API_KEY` 或 `.env` 文件设置。Router/Consumer 服务无需此配置，仅使用 Ollama 本地推理。

Nacos 默认开启了认证（`NACOS_AUTH_ENABLE=true`），初始化 Nacos 后需要先通过 Web 控制台（http://localhost:8848/nacos）使用默认账号 `nacos` / `nacos123` 登录。所有服务的 `application.yml` 已配置认证信息。

### Q: Agent 回答明显错误如何修正？

直接在对话中指出错误即可，例如"不对，故宫是8:30开门"或"你说错了"。目前修正需要手动追加到 `data/corrections/{agent}.md` 文件。参考文件头部注释的格式添加条目后，下次 Agent 回答相关问题时将自动参考修正记录。

计划在后续版本中支持用户通过对话自动记录修正。

### Q: Redis 连接被拒绝

Redis 默认启用了密码认证（`redis123`），所有服务已配置 `password: ${REDIS_PASSWORD:redis123}`。如需修改密码，需要同时更新 `docker-compose.yml` 和所有服务的 `application.yml`。

### Q: PostgreSQL 连接失败

确认 PostgreSQL 已启动且 `a2a_system` 数据库已存在。默认连接配置在 `smart-assistant-user` 和 `smart-assistant-consumer` 的 `application.yml` 中。

### Q: 编译报错 "Unable to rename"

这是由于 Windows 文件锁导致的，常见于之前有 Java 进程未完全退出。执行：

```powershell
taskkill /F /IM java.exe
# 然后重新编译
.\mvnw.cmd clean compile -DskipTests
```

### Q: 前端页面空白

确认前端服务已启动（端口 3001），检查 Vite 代理配置是否正确。前端通过 `/api` 前缀代理到 Gateway 的 8081 端口。

### Q: 启用 Nacos 认证后现有服务连接不上

Nacos 认证只在初始启动时生效。如果已存在的 Nacos 实例需要重启才能启用认证：

```powershell
docker-compose restart nacos
```

---

## 开发指南

### 模块依赖关系

```
smart-assistant-common (核心工具：分词器、SQL 校验器、Dotenv、修正记录服务、@Tool 日志切面)
    ↑          ↑          ↑          ↑
Gateway   Consumer    Router     Travel / Food / User / General
(无common)   (common)   (common)   (common)
```

### 添加新的 Agent

1. 在 `smart-assistant-{agent}` 模块中实现 `@Tool` 方法
2. 配置 `mcp-table-whitelist.yml` 中的表访问权限
3. 在 `application.yml` 中配置基础 Nacos 注册信息
4. Agent 启动后会自动通过 Nacos 注册到 Router 的服务发现列表
5. 在 Nacos UI 中创建配置 `{serviceName}-metadata` (Group: `AGENT_META`) 设置 keywords/priority
6. 后续修改 Agent 职责只需改 Nacos Config，无需重新部署

### 运行测试

```powershell
# 全量测试
.\mvnw.cmd test -DskipTests=false

# 指定模块
.\mvnw.cmd test -pl smart-assistant-gateway

# 指定测试类
.\mvnw.cmd test -pl smart-assistant-common -Dtest=SqlSecurityValidatorTest
```

当前测试覆盖：

| 模块 | 测试数 | 覆盖内容 |
|------|--------|---------|
| common | 63 | SQL 安全校验器、中文分词器...**AgentMemoryService(6用例)**、**TraceSpan(3用例)**、**GoldenTestRunner(5用例)** |
| gateway | 33 | JWT 工具、白名单过滤、Filter 认证、Filter 集成测试 |
| user | 9 | JWT 服务 |
| consumer | 25 | 对话叙事摘要、文档沉淀服务、DataGifTool、Chat 集成测试 |
| router | 95+ | Agent 调用、语义缓存...**IntentRetriever(1用例)**、**ExperienceValidator(5用例)**、**HandoffCommand(3用例)**、**KeywordFastRoute(12基准测试:7.3μs/次+多意图检测)**、**BadCaseMiner(6决策逻辑测试)**、**RouterServiceEndToEnd(8全链路集成测试)** |
| product | 11 | **ProductGraph(6图谱测试:15节点/36边/1.947μs)**、**RAG质量评分(5评分测试)** |
| order | 11 | **ApprovalStateMachine(11状态机测试:0.87μs/次/200线程并发安全)** |
| recommend | 5 | **RecommendEngine(5推荐引擎集成测试:图谱+协同过滤+去重+性能0.120ms)** |
| general | 44 | 数学计算、温度/长度/重量/货币转换、边界条件、**ScriptSandbox 沙箱(14测试)** |
| **总计** | **296+** | **46 个 P0-P4 基准测试，0 Failures**（含 P2 新增：ErrorType 错误分类、successCriteria 验收标准、BGE 语义合并、摘要回指、监控指标） |

---

## 生产就绪度

本项目已完成基于 [customer_work 12 生产坑](https://mp.weixin.qq.com/s/Ihtqsp68m1h66Ua12yV7kw) 和 [ThinkingAgent 可靠性体系](https://mp.weixin.qq.com/s/UTEdhrkV3G3Ycfrg0Jng_A) 的交叉审计。

详细检查清单见 [`docs/production-readiness-checklist.md`](docs/production-readiness-checklist.md)。

审计结论：**12 项核心条目全部通过**（4 项 ⚠️ 已在本次修复中处理）。

---

## 许可证

[MIT License](LICENSE)

Copyright (c) 2025-2026 SmartAssistant Project
