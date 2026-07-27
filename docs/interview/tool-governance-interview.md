# 工具治理（Tool Governance）面试问答 — 锚定 SmartAssistant 真实实现

> 配套：`ai-agent-interview-5d-qanda.md`（⑤ 框架原理 / ③ RAG）、`rag-deep-dive-interview.md`、`multi-agent-collaboration-interview.md`
> 本篇讲「工具如何被定义、注册、发现、执行与治理」——即 Agent 的能力边界与安全闸门。
> 所有类名 / 常量均为真实代码锚点（搜索即可定位）。

---

## 0. 一句话讲清（开场钩子）

> "我们项目的工具治理不是把 `@Tool` 方法直接丢给 LLM 就完事，而是分了**三个平面**：① 定义与注册平面（`ToolDefinition` + 中心 `tool-registry` + 本地 `ToolRegistry`）；② 执行治理平面（P0 统一 `ToolGateway` 网关 + Hook 链 + 熔断限流超时幂等）；③ 动态发现平面（`discover_tools` 元工具按需加载）。工具从「注册」到「被调用」全程经过治理链，高危操作走二阶段审批状态机。"

---

## 1. 整体架构（三平面 + 生命周期）

```
                        ① 定义与注册平面
   @Tool 方法 ──→ ToolDefinition(17字段) ──┬─→ 本地 ToolRegistry(ConcurrentHashMap, CORE 常驻)
                                            └─→ 中心 tool-registry:8088 (SHARED/EXTENSION 治理)
                                                     │  McpToolRegistryAdapter (仅发现, 不传递执行)
                                                     ▼
                                               search_tools (MCP 能力检索)

                        ③ 动态发现平面
   LLM ──→ discover_tools(capabilityQuery) ──→ McpRegistryDiscoveryClient.searchTools
        └─→ ToolGatewayToolCallback(def 感知) 注入 SmartReActAgent (护栏: 会话10次/动态15个)

                        ② 执行治理平面 (P0)
   每个 @Tool 调用 ──→ ToolGatewayToolCallback 装饰 ──→ ToolGateway.doExecute()
        status 拦截 → hooks(pre/approval) → 幂等 → scope/tag 鉴权 → 熔断 → 限流 → 超时(虚线程)
        → 审计 → hooks(post) → 幂等缓存 → 熔断恢复 → 调用计数
```

**生命周期（`ToolStatus` 枚举）**：`EXPERIMENTAL → ACTIVE → DEPRECATED → DISABLED → REMOVED`
- `EXPERIMENTAL`：仅授权 Agent 可见，非稳定 API
- `ACTIVE`：稳定可用
- `DEPRECATED`：仍可调用，响应带废弃警告头（含 `deprecatedBy` / `sunsetDate` 迁移指引）
- `DISABLED`：网关直接拦截，返回不可用错误
- `REMOVED`：仅留审计，返回工具不存在

> **核心设计点**：`ToolGateway` 对 `CORE` 层工具**常驻、不可被中心置为 DISABLED**（防止中心误杀导致 Agent 瘫痪）；`SHARED/EXTENSION` 受中心完整治理，中心宕机时自动降级为「仅 CORE 可用」。

---

## 2. 高频面试题 + 参考回答骨架（10 题）

### Q1：你们的工具是怎么定义和注册的？和 Spring AI 原生 `@Tool` 有什么区别？

**考点**：是否理解「治理元数据」与「功能方法」分离。

**骨架**：
- 功能方法用 Spring AI 的 `@Tool` 标注，但额外维护一个**不可变的 `ToolDefinition`**（`src/main/java/.../gateway/tool/ToolDefinition.java`），承载 17 个治理字段：风险等级、超时、是否需审批、最大重试、限流、scope、标签、版本、生命周期状态、能力标签、分层等。
- 注册有两层：启动时**本地 `ToolRegistry`**（内存 `ConcurrentHashMap`）做种子播种，供 `ToolGateway` 执行时查定义；`SHARED/EXTENSION` 工具同步注册到**中心 `tool-registry:8088`**。
- 区别：原生 `@Tool` 只有 name/description/inputSchema，没有风险/审批/生命周期/分层概念——这些全靠我们自己的 `ToolDefinition` 补上，是「工程化治理」和「玩具级」的分水岭。
- 快捷工厂：`ToolDefinition.read(...)` / `write(...)` / `highRisk(...)`，自动推导 capabilities（READ→`read-only`，HIGH→`mutate-state`+`payment`）。

### Q2：你们怎么控制「危险的工具」不乱调？（风险 + 审批）

**考点**：副作用管控、审批机制。

**骨架**：
- 风险四档：`ToolRiskLevel` = `READ / LOW / MEDIUM / HIGH`。HIGH 工具（退款、支付、删除）默认 `needsApproval=true`、`timeout=15s`、重试 1 次、限流 10/s。
- 审批走**二阶段状态机 `ApprovalService`**（`src/main/java/.../service/ApprovalService.java`）：
  1. `createApproval()` 创建 PENDING 记录（落 `approval_records` 表）；
  2. 用户确认 → `confirmAction()`（PENDING→CONFIRMED，充血状态机校验 + DB 原子 `WHERE status=pending` 兜底）；
  3. 执行时 `checkAndConsume()`（CONFIRMED→CONSUMED，调用一次即失效，防重放）。
- 网关侧 `ApprovalHook`（`@Order(20)`）在 preExecute 阶段对 `needsApproval=true` 的工具**异步先拒绝**（`APPROVAL_REJECTED`），不阻塞线程等人工——v2 规划审批工单 + 回调恢复。
- **诚实点**：当前 `ApprovalHook` 是「先拒绝」拦截，真正的二阶段确认由业务层 `ApprovalService` 串联（退款链路里 Agent 先调 `createApproval`，拿到确认后再执行）。可讲这是渐进式落地。

### Q3：P0 统一执行网关 `ToolGateway` 做了什么？为什么必须有？

**考点**：这是最强差异点，必背。

**骨架**：所有 `@Tool` 调用经 `ToolGatewayToolCallback` 装饰器路由到 `ToolGateway.doExecute()`，统一治理链：
1. **状态拦截**：`DISABLED`/`REMOVED` 直接拒绝（CORE 层按 ACTIVE 兜底）。
2. **Hook 链 preExecute**（按 `@Order`）：`ApprovalHook` 审批拦截、`ToolStatusHook` 等。
3. **幂等**：非只读操作按 `IdempotencyKey` 命中缓存直接返回，防重复执行。
4. **鉴权**：Scope 鉴权 + 标签鉴权（「或」关系，满足任一即放行）。
5. **熔断**：同一工具连续失败 ≥3 次 → 打开，后续请求快速拒绝（`recordFailure`/`recordSuccess`）。
6. **限流**：令牌桶 `RateLimiter`（`rateLimit`/s），用 `synchronized tryAcquire`。
7. **超时**：虚线程 `Thread.ofVirtual().start(task)` + `FutureTask.get(timeout)` 切断。
8. **审计日志** + **postExecute Hook 链**（脱敏 `SanitizeHook`、审计 `AuditHook` 链式改结果）。
9. 幂等缓存写回 + 熔断恢复 + 调用计数（`useCount` 原子）。

> **真实故事**：早期 `SmartReActAgent` 直接调 `ToolCallback.call()`，**完全绕过 `ToolGateway`**，导致上述治理从未生效。我们引入 `ToolGatewayToolCallback` 装饰器（T2c def 感知重载），把所有回调包一层——这是 P0 接线，修复了「治理悬空」的致命问题。

### Q4：熔断阈值 3 次、限流令牌桶——为什么要自己写，不用 Resilience4j？

**考点**：技术选型的权衡意识。

**骨架**：
- 多 Agent 协作那篇讲过，编排层用 Resilience4j 做服务级熔断（`DegradationService` 四级）。但**工具级**网关选择自研轻量熔断器 + 令牌桶，原因：
  - 工具粒度需要**按工具名隔离**（每个工具独立 `CircuitBreaker`/`RateLimiter`），自研 `ConcurrentHashMap` 更贴合；
  - 工具调用是**同步短操作**，虚线程 + `FutureTask.get(timeout)` 已能超时隔离，无需引入额外依赖；
  - 治理链是**单方法内顺序编排**（`doExecute`），自研比套 AOP/注解更直观、可观测（每一步都有 `log`）。
- 诚实补充：服务级（跨 Agent HTTP 调用）仍用 Resilience4j，工具级用自研——**分层选型**，不是非此即彼。

### Q5：工具的「动态发现」是怎么做的？为什么不全量预载？

**考点**：LLM 工具爆炸问题、按需加载。

**骨架**：
- 每个 Agent 只预载**核心 CORE 工具集**，避免 prompt 爆掉 / 工具选择混乱。
- 提供常驻元工具 `discover_tools`（`DiscoverToolsTool`，T2d）：LLM 推理时发现能力缺口，调 `discover_tools(capabilityQuery="sql-query")` 按需从中心 Registry 拉取并**动态注入**到 `SmartReActAgent`。
- 发现链路：`McpRegistryDiscoveryClient.searchTools` → `McpToolCallbackFactory.create(def)`（经 `ToolGatewayToolCallback` def 感知包裹）→ `toolRegistrar` 注入 Agent 运行时工具集。
- **护栏**（防失控）：会话级能力去重、每轮最多 1 次、每会话最多 10 次、动态工具总数上限 15。超限返回「已达上限，请基于已有工具或升级人工」。
- **降级**：Registry 不可用 → 返回「仅预载可用」，不阻断对话；同时记 `ToolGapEvent`（需求侧缺口，T2f）。

### Q6：中心 `tool-registry` 是怎么设计的？为什么「只发现不执行」？

**考点**：关注点分离、安全边界。

**骨架**：
- 中心 `tool-registry:8088` 是个独立服务，MCP server 暴露**发现层**：`McpToolRegistryAdapter` 把 `ToolDefinition` 映射成 MCP `Tool`（annotations 由 riskLevel 推导 `readOnlyHint`/`destructiveHint`/`idempotentHint`，能力写入 `_meta`）。
- **硬边界**：中心 MCP server **只暴露「发现」元数据，不实现传递式 `tools/call`**。每个目录工具注册为「可发现但拒绝执行」，其 call 处理器始终返回明确错误，指引调用方经各 Agent 的 `ToolGateway` 适配层执行。**仅 `search_tools` 是真正可调用（服务端检索）的 MCP 工具**。
- 为什么：① 治理集中在一处（`ToolGateway`），避免分散到各 Agent；② 执行权留在各 Agent（带 scope/审批/审计上下文），中心只做「能力黄页」；③ 安全：防止中心被当成万能执行器。

### Q7：能力标签 `functionalCapabilities` 是干嘛的？和 `capabilities`（风险）有什么区别？

**考点**：正交建模、受控词表。

**骨架**：
- `capabilities`（由 `riskLevel` 推导）描述「工具有多危险 / 碰什么资源」（read-only / mutate-state / payment）——用于**治理、授权、scope 鉴权**。
- `functionalCapabilities`（T1 新增，`ToolFunctionalCapability` 枚举）描述「工具能做什么业务动作」（如 `order-query` / `order-refund` / `sql-query`）——用于**能力作用域预载 + 自主发现匹配**。
- 二者**正交**：一个管「危险度」，一个管「能干什么」。
- `ToolFunctionalCapability` 是 **32 个 kebab-case 受控令牌**（`sql-query`、`product-query` 等）。v1 校验为 WARN-only（未知自定义词允许存在，迁移窗关闭后可收紧为 ERROR）——这是「治理严格度渐进」的好故事。

### Q8：工具的分层 `ToolTier`（CORE/SHARED/EXTENSION）有什么用？

**考点**：可用性来源、降级策略。

**骨架**：
- `CORE`：Agent 内部领域逻辑，常驻、不依赖中心、**不可被中心置 DISABLED**（兜底可用性）。
- `SHARED`：跨 Agent 共享基建（天气、图片、DataGif 等），中心治理、多 Agent 复用。
- `EXTENSION`：插件/第三方/动态加载/实验性工具，中心全量治理（status/approval/rateLimit/compat/deprecation/health）。
- 执行路径影响（`ToolRegistryClient.getToolCallbacks`）：三层 merge——CORE 常驻，SHARED/EXTENSION 需中心 allowlist 过滤；中心不可用时仅 CORE 保证可用。**这把「微服务可用性分层」复用到工具上**。

### Q9：工具版本怎么管？废弃工具怎么平滑下线？

**考点**：生命周期治理、迁移策略。

**骨架**：
- `ToolDefinition` 带 `version`、`status`、`deprecatedBy`、`sunsetDate`、`ownerTeam`、`namespace`。
- 废弃流程：`ACTIVE → DEPRECATED`（响应头带警告 + `deprecatedBy` 指新工具）→ 观察期 → `DISABLED`（网关拦截）→ `REMOVED`（仅留审计）。
- 调用方迁移有 `sunsetDate` 明确时间点；`deprecatedBy` 给替代工具名，LLM 可据此自动切换。
- 诚实点：当前**版本治理是覆盖式、无审核/回滚**（这是已知短板 Q7，规划中补审核 + 回滚）。可讲「先有生命周期状态机，审核/回滚是下一期」——体现你有演进意识。

### Q10：工具和「多 Agent 协作」「RAG」怎么联动？

**考点**：体系化串联（收尾题，展现全局观）。

**骨架**：
- 多 Agent：每个 Agent 的工具经 `ToolRegistryClient` 按域标签（ORDER/PRODUCT/GENERAL）拉取，统一经 `ToolGateway` 治理；跨 Agent 调用走 A2A，但**工具治理链在各自 Agent 的网关内闭环**。
- RAG：`knowledge-retrieve` / `product-knowledge` 等是受控 `functionalCapabilities`，既是 RAG 检索入口，也是可被发现的能力令牌——RAG 检索工具本身受 `ToolGateway` 的超时/限流/审计约束。
- 一句话：**工具治理是横切关注点，RAG 检索、Agent 协作都是「被治理的工具」的具体形态**。

---

## 3. STAR 速记表（讲项目时直接套）

| 情境(S) | 任务(T) | 行动(A) | 结果(R) |
|---|---|---|---|
| `@Tool` 直接调用绕过治理，危险操作无审批/熔断 | 建统一工具治理平面 | 引入 `ToolGateway` + `ToolGatewayToolCallback` 装饰器 + Hook 链 + 熔断/限流/超时/幂等 | 所有工具调用 100% 经治理链，发现并修复「治理悬空」致命 bug |
| 退款等高危操作需人工确认 | 实现副作用审批 | `ApprovalService` 二阶段状态机（PENDING→CONFIRMED→CONSUMED）+ `ApprovalHook` 先拒绝 | 高危工具不可被 LLM 直接执行，需显式确认 |
| 工具全量预载导致 prompt 爆炸 | 按需动态发现 | `discover_tools` 元工具 + `McpRegistryDiscoveryClient` + 护栏（会话10/动态15） | Agent 工具集按需伸缩，不牺牲能力覆盖 |
| 中心 Registry 单点风险 | 分层 + 降级 | `ToolTier` 三层（CORE 常驻不可禁用）+ 中心不可用时仅 CORE 可用 | 中心宕机不瘫 Agent，可用性分层清晰 |

---

## 4. 主动抛出的 3 个设计决策（体现深度）

1. **「CORE 常驻不可禁用」**——宁可让本地领域工具绕过中心，也要保证 Agent 在中心宕机时仍能跑核心逻辑。这是可用性优先于集中管控的取舍。
2. **「中心只发现不执行」**——执行权留在各 Agent 的 `ToolGateway`，中心只做能力黄页。治理集中 + 执行分散，避免单点执行器。
3. **「自研工具级熔断/限流而非全用 Resilience4j」**——工具粒度按名隔离、同步短操作，自研比 AOP 更直观可观测；服务级跨 Agent 调用仍用 Resilience4j（分层选型）。

---

## 5. 深水区追问（面试官可能追）

**Q：网关的熔断为啥是「连续失败 ≥3 次」就开，没有半开态？**
A：当前是简化版（开/关两态），没有 Resilience4j 的半开探测。设计上靠 `recordSuccess` 直接移除熔断记录（全恢复），对工具级短操作够用。后续可引入半开态（少量探活请求）降低误杀——这是已知简化点，诚实说明即可。

**Q：`ApprovalHook` 只「先拒绝」，那确认后怎么恢复执行？**
A：当前是「拦截式」，真正的二阶段由业务层 `ApprovalService` 串联——退款链路里 Agent 先 `createApproval` 拿确认，用户确认后再次调用才 `checkAndConsume` 放行。v2 规划审批工单 + 网关回调自动恢复，避免业务层手动编排。

**Q：discover_tools 动态注入的工具，治理链还生效吗？**
A：生效。发现后组装的回调经 `ToolGatewayToolCallback` **def 感知重载**（传入中心目录的完整 `ToolDefinition`，含 status/rateLimit/scopes），直接走 `ToolGateway.execute(def, ...)` 重载，不会退化成 CORE 默认定义——这是 T2c 的 P0 硬要求（防止 MCP-backed 工具绕过中心治理）。

**Q：能力词表 `ToolFunctionalCapability` 只有 32 个，不够用怎么办？**
A：v1 是 WARN-only 宽松校验，未知自定义词允许存在（不抛异常），由 `ToolManifestValidator`（T6）决定是否告警。迁移窗关闭后可收紧为 ERROR。词表在 `ToolFunctionalCapability.java` 集中维护，扩展只需加枚举值。

---

## 6. 避坑清单（面试别踩）

- ❌ 别把「Spring AI `@Tool`」等同于「工具治理」——治理元数据在 `ToolDefinition`，不是注解本身。
- ❌ 别把 `ToolGateway` 和 `DegradationService`（多 Agent 服务级熔断）混为一谈——前者是**工具级**，后者是**服务级**，两套独立。
- ❌ 别讲「审批是自动通过的」——当前 `ApprovalHook` 是**先拒绝**，确认链路在业务层。
- ❌ 别把 `discover_tools` 说成「无限发现」——有护栏（会话 10 次 / 动态 15 个 / 去重），否则会被追问失控风险。
- ✅ 强调「治理悬空 bug 的修复」——这是体现你 engineering rigor 的最佳素材。

---

## 7. 速记表（30 秒过一遍）

| 维度 | 真实实现（一句话） |
|---|---|
| 定义模型 | `ToolDefinition`（17 字段：风险/超时/审批/限流/版本/状态/能力/分层） |
| 生命周期 | `ToolStatus`：EXPERIMENTAL→ACTIVE→DEPRECATED→DISABLED→REMOVED |
| 风险 | `ToolRiskLevel` READ/LOW/MEDIUM/HIGH；HIGH 默认需审批、15s、限流 10/s |
| 分层 | `ToolTier` CORE（常驻不可禁用）/SHARED/EXTENSION（中心治理） |
| 中心注册表 | `tool-registry:8088`，MCP 仅发现不执行；`search_tools` 可调用 |
| 执行网关 | `ToolGateway` 治理链：状态→Hook→幂等→鉴权→熔断(≥3)→限流→超时(虚线程)→审计→Hook(post) |
| 装饰器 | `ToolGatewayToolCallback` 包裹每个 `@Tool`，修复「治理悬空」 |
| 审批 | `ApprovalService` 二阶段状态机 + `ApprovalHook` 先拒绝 |
| 动态发现 | `discover_tools` 元工具 + 护栏（会话10/动态15/去重）+ 降级 |
| 能力标签 | `functionalCapabilities`（32 kebab-case 受控词表）与 `capabilities`（风险）正交 |

> 建议叙事顺序：**先讲三平面总览 → 展开执行网关（Q3，最强差异）→ 审批与 Hook 链 → 中心 Registry 只发现不执行 → 动态发现 → 用生命周期/分层收尾**。

---

*配套阅读：`ai-agent-interview-5d-qanda.md`、`rag-deep-dive-interview.md`、`multi-agent-collaboration-interview.md`。RAG 是「检索能力」，多 Agent 是「调度能力」，工具治理是「能力边界与安全闸门」——三者共同撑起完整项目闭环。*
