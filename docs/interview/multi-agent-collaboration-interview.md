# 多 Agent 协作 — 面试问答深度模板（锚定 SmartAssistant 真实实现）

> 适用岗位：AI / LLM 应用开发、Agent 平台 / 多智能体架构方向。
> 用法：每题「参考回答骨架」用你自己的话串成 1–2 分钟口语；STAR 素材是真实代码锚点，背不下来至少记住类名。
> 定位：本项目多 Agent 协作是**自研编排层 + 标准微服务治理**的混合体，不是套 LangGraph / AutoGen。这是和大多数候选人拉开差距的叙事核心。

---

## 0. 一分钟讲清（开场钩子）

> "我们项目的多 Agent 协作分两层：**跨服务协作**和**单 Agent 内部推理**。
> 跨服务层我们自研了一套基于**意图图（DAG）的执行引擎** `GraphExecutionService`，由 Router 做意图拆解与路由，多个领域 Agent（订单/商品/通用/旅行/美食）通过 Nacos 注册发现、HTTP 直调协作，支持并行执行、显式 Handoff 交接、熔断降级和断点恢复；单 Agent 内部用自研 `SmartReActAgent` 跑工具调用循环。
> 整套没有依赖外部 Agent 框架，而是把**微服务那套注册发现、熔断、负载、可观测**直接复用到 Agent 协作上——这是我们应对生产稳定性的核心思路。"

---

## 1. 整体数据流（一句话 + 链路）

```
用户 → Consumer(Gateway) → Router.route()
        │
        ├─ 单意图 → 直调目标 Agent（SmartRoutingService 或 LLM 路由打分）
        │
        └─ 多意图 → RouteExecutionService.executeCollaborative()
                        │
                        ├─ TaskPlannerService.planToGraph(question)  → IntentGraph(DAG，带依赖/条件边)
                        ├─ GraphExecutionService.executeWithResume()  → 按拓扑并行执行节点
                        │       ├─ 节点 = AgentCallerService.callAgentAndExtractTitles()  (HTTP 直调 / A2A)
                        │       ├─ 共享上下文 buildSharedContext() 注入下游节点
                        │       ├─ HandoffCommand 显式交接（串行）
                        │       ├─ 节点级熔断 / 指数退避重试 / 验收不通过重规划
                        │       └─ Checkpoint 每轮持久化（断点恢复）
                        └─ ResultMerger.merge()  → LLM 整合多源结果（摘要优先 + 来源标注）
```

**一句话**：Router 把多意图拆成有依赖的 DAG → 引擎按层并行调度各 Agent（带上下文传递 / 交接 / 熔断 / 恢复）→ 最后合并成统一回答。

---

## 2. 高频面试题 + 参考回答骨架

### Q1：你们的多 Agent 是怎么"协作"的？用了什么框架？
**骨架**：
- 没用 LangGraph / AutoGen / CrewAI 这类现成框架，而是**自研编排层**复用了微服务的治理手段。
- 三个支柱：① **注册发现**（Nacos，Agent 自报元数据：能力/关键词/优先级/版本）；② **调度编排**（DAG 执行引擎，依赖感知的并行 + 串行）；③ **通信协议**（A2A 思路，但落地为自定义 HTTP 直调，而非标准 A2A SDK）。
- 单 Agent 内还有一层 `SmartReActAgent`（自研 ReAct 循环，替代 Spring AI 的 ReactAgent）。
**STAR**：可以作为所有问题的总纲。

### Q2：为什么用 DAG（意图图）而不是让一个 Agent 串行挨个调用？
**骨架**：
- 多意图问题里很多子任务**互不依赖**（"北京天气"和"北京景点"），串行是浪费——DAG 按层 `CompletableFuture` 并行，耗时 = `Max(各并行分支)`。
- 但有依赖的必须串行且共享上下文（"先查景点，再推荐景点附近餐厅"）——DAG 的 `dependsOn` 保证顺序，`buildSharedContext()` 把上游结果注入下游 prompt。
- 还支持**条件边**和**重路由（循环）**：节点结果满足某条件时跳转到另一节点，用 `maxGraphIterations` 防无限循环。
**锚点**：`IntentGraph.getExecutableNodes()`（拓扑 + 条件依赖评估）、`GraphExecutionService.execute()`（`CompletableFuture.allOf` 分层等待）。

### Q3：Agent 之间怎么通信？为什么不全用标准 A2A 协议？
**骨架**：
- 通信是 **Router 作为编排中枢，直接 HTTP 调各 Agent 的 `/api/order/agent/process`**（自定义协议，不是标准 A2A SDK）。
- 原因（坦诚讲）：标准 A2A 框架在我们这套**自研 + 离线 Ollama** 栈里引入成本大于收益；我们用「HTTP + 结构化 JSON + 版本协商」实现了 A2A 的核心诉求——**能力发现 + 寻址 + 协议兼容**，但省掉了框架绑定。
- 异步场景用 Redis List 事件总线（`AgentEventBus`）做 Handoff 解耦（Router 发布、Agent BLPOP 消费）。
**锚点**：`AgentCallerService`（RestTemplate，连接 3s / 读取 5s 超时）、`AgentVersionNegotiator`（按 `clientVersion` + `protocolVersion=a2a-v1` 选兼容实例，选最高版本）。

### Q4：Agent 怎么知道对方存在、能做什么？
**骨架**：**注册发现模式**。
- 各 Agent 启动时在 Nacos 注册，metadata 里声明 `agent-type / keywords / capabilities / priority / version / cache-ttl-seconds` 等。
- `AgentDiscoveryService` 订阅 Nacos 命名事件，服务上下线自动刷新本地 `agentCache`，并把 SSE URL 映射写 Redis。
- 路由时按关键词/能力/优先级打分匹配（`SmartRoutingService.calculateScore`：关键词 40% + 意图 30% + 上下文 20% + 优先级）。
- 降级 Agent = 优先级最高的那个（General 优先级 100，远高于其他 10）。
**锚点**：`AgentDiscoveryService.subscribeAllMatchingServices()`（动态订阅）、`refreshFallbackAgent()`（priority 最大者兜底）。

### Q5：一个 Agent 调失败了怎么办？怎么防止雪崩？
**骨架**：**三层防护**（这是生产级差异点，重点讲）：
1. **节点级熔断**：同 Agent 在本次 Graph 中连续失败 ≥ 2 次（`NODE_LEVEL_BREAKER_THRESHOLD=2`），后续指向它的节点直接跳过，避免"异常制造更多异常"。
2. **重试 + 退避**：单节点最多 3 次重试，指数退避 1s→2s→4s；异常分类（`classifyException`）区分可重试（超时/连接拒绝）与致命。
3. **调用级熔断**：`AgentCallerService` 用 Resilience4j `@CircuitBreaker`，连续失败率超阈值直接 fallback，不再打下游。
4. **整体降级**：`DegradationService` 按异常率分级：NORMAL → LIGHT（跳过 DAG，只调 general_agent）→ HEAVY（直接用本地 Ollama 兜底 `inlineFallback`）→ HALF_OPEN（半开探活）。
**锚点**：`GraphExecutionService.executeNode()`（熔断 + 重试 + 验收）、`DegradationService.DegradationLevel` 枚举。

### Q6：Handoff（交接）和普通的并行路由有什么区别？
**骨架**：
- 并行路由是 Router 一次性拆好 DAG，各 Agent 平等执行；**Handoff 是串行链式**——Agent A 处理完，主动发 `HandoffCommand(targetAgent, question, contextPayload)` 把**累积上下文**交给 B，B 在 A 的基础上继续。
- 典型场景：General 检测到复杂订单查询 → `HandoffCommand(order_agent)`；Order 发现商品问题 → `HandoffCommand(product_agent)`。
- 类型：`HANDOFF`（继续）/ `COMPLETE`（结束）/ `FAILED`（尝试其他或终止）。
- 支持同步（HTTP 等待）和异步（Redis 事件总线，不阻塞）。
**锚点**：`HandoffCommand` record、`GraphExecutionService.processHandoffs()` / `executeWithHandoff()`。

### Q7：多个 Agent 的结果怎么合并？上下文不会爆吗？
**骨架**：
- `ResultMerger` 用**摘要优先**策略：合并时优先用每个子任务的 `summary`（前 200 字符）拼上下文，**完整结果存 Redis**（`a2a:task-result:{taskId}`，TTL 300s），需要细节时通过 `getAsyncTaskResult` 工具按需查。
- 让 LLM 做最终整合，要求：按逻辑组织、去重、标注来源（"根据景点攻略推荐"）、**严禁编造引用**。
- 兜底：LLM 挂了就简单拼接。
**锚点**：`ResultMerger.merge()`、`storeFullResults()`（Redis 按需查询，省 token）。

### Q8：任务执行到一半服务挂了，能恢复吗？
**骨架**：**能，靠 Checkpoint**。
- `GraphExecutionService` 每轮执行完都把 `completedMap / breakerFailureCounts / round` 等状态通过 `GraphCheckpointService` 持久化（Redis）。
- 用同一 `requestId` 重新进入 `executeWithResume()` 时自动恢复，已完成节点不再重跑。
- 对标 LangGraph 的 Resume 能力，但我们是用 `ConcurrentHashMap` 状态 + Redis 自己实现的。
**锚点**：`executeWithResume()`、`GraphCheckpointService.restoreCheckpoint()` / `saveCheckpoint()`、HITL 节点也会先存 Checkpoint 等审批。

### Q9：单 Agent 内部是怎么决定调工具的？和编排层什么关系？
**骨架**：
- 内部是 `SmartReActAgent`——自研的 ReAct 循环，替代 Spring AI Alibaba 的 ReactAgent，因为要完全可控。
- 特点：最大 10 轮迭代、60s 超时、Token 预算（上下文窗口 0.8，超了自动压缩）、同工具同参数去重（防故障重放风暴）、工具幻觉报错让 LLM 自纠、工具错误返回 `retryable` 结构化错误。
- 还支持**按入口分级**（`ReActProfile`：order/general/product/mcp 各有不同步数/预算/并发），实现"复杂任务给多步、简单任务收紧"。
- 它和编排层是**嵌套关系**：编排层决定"调哪个 Agent、以什么顺序"，Agent 内部决定"调什么工具、调几轮"。
**锚点**：`SmartReActAgent`（DEFAULT_MAX_ITERATIONS=10、DEFAULT_TOKEN_BUDGET_RATIO=0.8、withProfile）。

### Q10：可观测性怎么做？出问题怎么定位？
**骨架**：
- 全链路 `requestId` / `threadId` 透传（`DistributedTracingService`）。
- 每个 Graph 节点执行把进度事件（node_started / node_completed / node_failed / node_replan）推到 Redis List，供 Consumer SSE 转发前端实时展示。
- `AgentEventBus` 记录 Agent 状态机迁移；`OpsMetrics` 按 Agent 统计错误类型；`AgentHeartbeatService` + Nacos 心跳做健康探测。
**锚点**：`GraphExecutionService.storeNodeProgressEvent()`（SSE_EVENTS_KEY_PREFIX）、`AgentHeartbeatService.beat()` / `markCompleted()`。

---

## 3. 三个值得主动抛出的"设计决策"（加分项）

| 决策 | 我们怎么做 | 为什么（面试话术） |
|---|---|---|
| **A2A 自研协议** | HTTP 直调 + 版本协商，非标准 SDK | 离线 Ollama 栈 + 自研体系，框架绑定成本高；自研已实现 A2A 三大诉求（发现/寻址/兼容） |
| **DAG 而非串行/黑盒 LLM 编排** | 显式 `IntentGraph` 依赖图 | 可控、可并行、可恢复、可观测；失败可定位到具体节点而非整段重试 |
| **微服务治理复用到 Agent** | 注册发现/熔断/降级/心跳照搬 | Agent 本质是"会推理的微服务"，用成熟手段治理最稳 |

---

## 4. 深水区追问（预判 + 应答）

- **"DAG 是 LLM 生成的，生成错了（环/不可达）怎么办？"** → `hasDeadlock()` 检测（连续 2 轮无进展即终止）；`MAX_STALE_ROUNDS=2` 防卡死；非法节点 `removeNode` 时重算入度。
- **"Handoff 形成环怎么办？"** → 节点执行次数上限 `MAX_NODE_EXECUTIONS=3`，超限不再重路由。
- **"版本协商失败会怎样？"** → `AgentVersionNegotiator` 返回 null 时 `AgentCallerService.findAgentUrl()` 回退到按名匹配；都找不到返回错误字符串由上游兜底。
- **"并行节点同时写共享状态冲突？"** → 共享状态用 `ConcurrentHashMap` + 不可变 `SubTaskResult`，节点只写自己的结果槽，上下文是只读快照注入。
- **"如果 LLM 把 Handoff 指令写进正文而不是结构化命令？"** → 解析失败时该结果按普通节点处理，不触发交接，靠验收标准 + 重规划兜底。

---

## 5. 避坑清单（讲出来显老练）

1. **不要把 Agent 当成无状态函数**——它有状态、会超时、会雪崩，必须上熔断/降级。
2. **不要全量拼接上下文**——多 Agent 结果用摘要 + 按需查询，否则 token 爆炸（我们踩过，后来改成 `ResultMerger` 摘要优先）。
3. **不要信任 LLM 生成的 DAG 无脑执行**——必须有死锁/循环/次数预算兜底。
4. **不要只做"调用成功"判断**——我们加了"验收标准"（`ReflectionService.checkCriteria` → `NEED_REPLAN`），输出不满足要求才重规划，而不是只看 HTTP 200。
5. **降级链要闭环**——从节点级 → 调用级 → 服务级（HEAVY 直接用本地模型），一层层收窄，保证"永远有回答"。

---

## 6. 速记表（一眼回顾）

| 维度 | 真实实现 | 一句话 |
|---|---|---|
| 编排模型 | `IntentGraph`（DAG）+ `GraphExecutionService` | 依赖感知并行 + 串行 Handoff |
| 注册发现 | Nacos + `AgentDiscoveryService` | 动态订阅、优先级兜底 |
| 通信协议 | 自定义 HTTP `/api/order/agent/process` + `AgentVersionNegotiator` | 轻量 A2A，不绑框架 |
| 重试/熔断 | 节点级阈值 2 + 重试 3 次退避 1/2/4s + Resilience4j | 三层防雪崩 |
| 降级 | `DegradationService` NORMAL→LIGHT→HEAVY→HALF_OPEN | 永远有回答 |
| 交接 | `HandoffCommand` HANDOFF/COMPLETE/FAILED | 串行链式 + 上下文传递 |
| 结果合并 | `ResultMerger` 摘要优先 + Redis 按需 | 省 token + 来源标注 |
| 断点恢复 | `GraphCheckpointService`（Redis） | executeWithResume 续跑 |
| 内部引擎 | `SmartReActAgent` 10 轮/60s/0.8 预算 | 自研 ReAct 替代框架 |
| 可观测 | requestId + SSE 节点事件 + 心跳 | 全链路定位 |

---

## 7. 和前面模板的衔接

- 第③点"生产级差异"在本项目落地就是这套协作层（不是 RAG 专属）。
- 面试时建议叙事顺序：**先讲整体两层架构（Q1/Q2）→ 重点展开"失败怎么办"（Q5，最强差异点）→ 再讲 Handoff/恢复/合并（Q6/Q7/Q8）**，最后用 Q9 收尾嵌套关系。
- 与 `rag-deep-dive-interview.md` 互补：RAG 是"检索能力"，本篇是"多 Agent 调度能力"，共同撑起"完整项目闭环"。
