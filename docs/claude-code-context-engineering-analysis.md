# Claude Code 上下文管理（Context Engineering）与 SmartAssistant 对比分析

> 分析对象文章：《面试官坏笑："Claude Code 上下文管理是怎么做的？"，我："满了就压缩不就行了么？"》
> 作者：小 G（「小G JavaGuide」），2026-07-27
> 核心主题：Coding Agent 的 **Context Engineering**（上下文工程）——窗口预算、信息加载、上下文退化、四级治理（清理/压缩/重置/隔离）、长任务落地与面试回答。

---

## 〇、结论速览

**SmartAssistant 的上下文管理体系并非空白，而是已经高度自觉地按同一套 Anthropic/Harness-Engineering 思想构建**——代码注释里多次直引「参考文章④⑤⑧」（Handoff 模式、子任务三层注入、分层记忆）。因此本文不是「从零对标」，而是**校验已落地程度 + 定位剩余差距**。

| 维度 | 文章主张 | SmartAssistant 现状 | 结论 |
|---|---|---|---|
| 窗口预算 | `有效窗口 = 模型窗口 − 预留摘要`，按 token 设 AUTOCOMPACT/WARNING/ERROR/MANUAL 缓冲带 | `TokenBudgetService`（L1/L2/L3 **成本**预算）+ `SmartReActAgent` 的 `contextWindow*tokenBudgetRatio` **硬中止** | ⚠️ **预算维度有，但触发粒度错位**：压缩按「消息数」触发，未做 token 级缓冲带 |
| 信息分层加载 | System/Prompt/MCP 延迟加载 + Progressive Disclosure | `ContextOrchestrator` 四层预算（状态/短期/中期/长期视角）+ RAG 注入 | ✅ 领先/对齐 |
| 工具结果清理 | 大结果写盘 + 窗口留预览（阈值 50K 字符）+ MicroCompact | 无独立工具结果预算；工具结果混在消息历史，仅在全量摘要时压缩 | ⚠️ **缺工具结果预算层** |
| 历史压缩 | 五级渐进流水线（写盘→Snip→MicroCompact→Collapse→AutoCompact） | 仅一级：全量 LLM 摘要（9 段式）+ 后台预压缩 | ⚠️ **缺便宜前置档（0-3 级）** |
| 压缩钩子 | PreCompact/PostCompact Hook | `CompressionHooks`（beforeCompress/afterCompress） | ✅ 对齐 |
| 重置/交接/隔离 | Context Reset + Handoff 文档 + Sub-agent 深度隔离 | `HandoffCommand`（HANDOFF/COMPLETE/FAILED + contextPayload）、`SubTaskResult`（summary/result 分离）、`GraphExecutionService` DAG | ✅ 对齐（Handoff 文档结构化程度略弱） |
| 断路器 | 连续 AutoCompact 失败 ×3 跳过；Sub-agent 深度 >5 禁 Agent 工具 | `DegradationService` 滑动窗口错误率熔断（NORMAL/LIGHT/HEAVY + HALF_OPEN 探测） | ✅ **更超前**（面向全链路而非仅压缩） |
| 持久记忆 | Session Memory（~10K，硬上限 12K）+ Auto Memory（文件） | `ConversationSummaryStore`（Milvus 向量化 + 语义检索）+ `summaryChain` | ✅ **更超前**（摘要可检索） |

**一句话**：SmartAssistant 在「成本预算/熔断/子任务隔离/DAG/DAG 检索式记忆」上已**等于或超过**文章；在「token 级窗口预算触发、工具结果预算、多级渐进压缩、结构化 Handoff 文档、压缩失败断路器」上存在**明确可补差距（G1–G6）**。

---

## 一、文章核心观点（提取）

1. **Context Engineering ≠ Prompt Engineering**：前者管整个会话的信息流动，后者只管单次输入。System Prompt 过长=固定开销，让窗口更早进入高压区。
2. **Agent = Model + Harness**：模型管推理，Harness 管信息获取/工具/推进；上下文管理属于 Harness。后端类比：上下文窗口≈JVM 堆、Compaction≈GC、Context Reset≈进程重启+检查点、Sub-agent≈微服务、工具结果清理≈LRU 淘汰。
3. **有效窗口**：`effectiveWindow = modelWindow − reservedForSummary(min(maxOutputTokens,20000))`；缓冲常数 `AUTOCOMPACT_BUFFER=13K`、`WARNING/ERROR_BUFFER=20K`、`MANUAL_COMPACT_BUFFER=3K`。
4. **上下文退化三现象**：Lost in the Middle（中间信息被漏读）、Context Rot（旧判断/重复搜索/无用日志降信噪比）、Context Anxiety（将满时草草收尾）。
5. **治理优先序**：先清可重取的工具结果 → 再压缩历史 → 支线交子代理 → 仍接不住则 handoff + Reset。**越靠后代价越高**。
6. **五级渐进压缩**：0 大结果存盘 / 1 Snip / 2 MicroCompact / 3 Collapse(90% 利用率激活,95% 禁 spawn) / 4 AutoCompact(全量摘要)。成本与信息损失逐级上升。
7. **状态外化**：Tasks API 持久化任务列表；记忆分层（会话历史易压 / CLAUDE.md 指令 / Auto Memory 启动载前 200 行）。**只记源码查不到的**（偏好、决策原因），不记目录/签名/版本。

---

## 二、SmartAssistant 现状逐点映射（含代码证据）

### ✅ 已落地 / 对齐

| 文章概念 | SmartAssistant 实现 | 证据 |
|---|---|---|
| 分层记忆 + 预算分配 | `ContextOrchestrator`：状态锚点 500T / 短期 2500T / 中期 1500T / 长期 2000T / 问题 1500T（总 8K） | `ContextOrchestrator.java:37-43` |
| 长任务压缩（全量摘要） | `ContextCompressor`：9 段式结构摘要（诉求/技术上下文/文件/错误/过程/反馈/已完成/待办/下一步），增量 `summaryChain` | `ContextCompressor.java:34-54, 134-141` |
| 保留近期上下文 | `findKeepStart()` 保留末尾最近 3 个工具结果 | `ContextCompressor.java:179-192` |
| 压缩钩子 | `CompressionHooks.beforeCompress/afterCompress` | `CompressionHooks.java:23-29` |
| 成本预算 + 软降级 | `TokenBudgetService` L1 全局 / L2 应用 / L3 用户，超限不硬拒、降级模型 | `TokenBudgetService.java:66-95` |
| 子任务隔离 + 三层注入 | `SubTaskResult`：`summary`(前200字, 入上下文) + `result`(全量, 按需查询) | `SubTaskResult.java:16-20, 83-91` |
| 显式 Handoff | `HandoffCommand`：HANDOFF/COMPLETE/FAILED + targetAgent + question + contextPayload | `HandoffCommand.java:25-39` |
| DAG / 计划模式 | `GraphExecutionService`（IntentGraph 编排） | `GraphExecutionService.java` |
| 全链路熔断 | `DegradationService`：60s 滑动窗口错误率，NORMAL→LIGHT(20%)→HEAVY(40%)+HALF_OPEN 探测恢复 | `DegradationService.java:60-247` |
| 可检索持久记忆 | `ConversationSummaryStore` + `MilvusConversationSummaryStore`：9 段摘要向量化 + 语义检索 | `ConversationSummaryStore.java:24-68` |
| 失败分类差异化恢复 | `SubTaskResult.ErrorType`：RETRYABLE/FATAL/NEED_REPLAN | `SubTaskResult.java:34-43` |

### ⚠️ 差距（G1–G6，按优先级）

**G1 · token 级窗口预算触发（高优，最实质）**
- 现状：`SmartReActAgent` 压缩触发是 **消息数阈值**（`messages.size() > 20`，外加 prefill 基线推算的 scoped 阈值），`maxBudgetTokens = contextWindow*tokenBudgetRatio`（line 552）仅作**硬中止**（超预算直接 `resolveUserMessage` 报错返回），**不触发压缩**。
- 问题：消息数 ≠ token 数。一条带大工具结果的消息可能占满窗口，但消息数未达 20 → 不触发压缩 → 直接撞硬上限报错（即文章说的 Context Anxiety 草草收尾 / 崩溃）。
- 建议：把压缩触发改为 **token 利用率**驱动：`autoCompactThreshold = effectiveWindow − 13000`（对齐文章 AUTOCOMPACT_BUFFER），`warningThreshold = autoCompact − 20000`；`effectiveWindow = contextWindow − reservedForSummary`。保留现有「消息数硬兜底」作为最后防线。

**G2 · 多级渐进压缩（中高优，降本增效）**
- 现状：只有一级全量 LLM 摘要。每次压缩都调一次 LLM（成本高、有延迟）。
- 建议：在 AutoCompact 之前插入便宜档：
  - **0 级·工具结果写盘**：大工具结果（如长 RAG 返回、长文件读）超阈值（如 8K 字符）落 Redis/本地，窗口仅留预览 + 指针（`[tool-result@id]`），按需重新取。
  - **1 级·Snip**：删历史中已完成的工具往返 range，重连消息链。
  - **2 级·MicroCompact**：旧工具结果替换为 `[Old tool result content cleared]`。
  - **3 级·Collapse**：已完成子任务折叠为 `SubTaskResult.summary` 快照。
  - 仅当上述仍接不住才走现有 AutoCompact（全量摘要）。
- 收益：绝大多数长会话在 0–2 级解决，省下大量 LLM 摘要调用。

**G3 · 工具结果预算（中优，G2 的前提）**
- 现状：工具结果作为普通 `ToolResponseMessage` 混在 `messages`，无法单独计量/淘汰。
- 建议：引入 `ToolResultBudget`（类似文章 `mustReapply/frozen/fresh` 状态 + 50K 字符阈值），对 Bash/Grep/RAG 等大结果做「写盘+预览」处理。这是 G2 第 0 级的基础。

**G4 · 结构化 Handoff 文档（中优）**
- 现状：`HandoffCommand.contextPayload` 是自由字符串，无强制结构。
- 建议：把交接负载结构化（对齐文章 Context Reset 文档）：`{ goal, completed, breakpoint, constraints, excluded, failures, startupActions }`，接收方据此重建上下文而非重放全历史。

**G5 · 压缩失败断路器 + 子代理深度上限（低中优）**
- 现状：有全链路 `DegradationService` 熔断，但**无「连续 AutoCompact 失败跳过」**专用保护；子任务/子 Agent 无深度上限。
- 建议：连续压缩失败 ≥3 次跳过自动压缩、降级为「保留最近 N 轮 + 截断旧历史」的确定性策略，避免压缩链路自陷。子 Agent spawn 设深度上限（如 5）。

**G6 · Lost-in-the-Middle 缓解（低优）**
- 现状：压缩后摘要置前、保留最近 3 工具结果，属隐式缓解；无显式重要性钉选。
- 建议：对「关键决策/用户硬约束/当前失败用例」做 importance-pinning（压缩时不丢、置顶），降低中间信息被漏读风险。

---

## 二-B、G1–G6 实施状态（2026-07-27 已落地 ✅）

> 全部 6 项差距已实现并通过 `common` + `router` 模块编译（`mvn test-compile` BUILD SUCCESS）；
> 其中 G4 有单元验证（`HandoffCommandTest` 5/5 通过）。改动均为**增量、向后兼容**，未触碰生产调用方。

| 项 | 状态 | 落地文件 / 关键改动 |
|---|---|---|
| **G1** token 级窗口预算触发 | ✅ | `SmartReActAgent`：压缩触发由「消息数」改为 **token 利用率**三触发（Scoped 净增长 90% / 剩余窗口 < 13K 缓冲带 / 占用率 > 95%），`prefillTokenBaseline` 改 token 口径；新增 `TokenEstimator.estimateMessages(...)` |
| **G2** 多级渐进压缩 | ✅ | 新增 `ProgressiveContextCompressor`：先 **SNAP 廉价裁剪**（无 LLM），仅当仍超预算才升级 **LLM 全量摘要**；`SmartReActAgent` 压缩/预压缩均走渐进器 |
| **G3** 工具结果预算 | ✅ | 新增 `ToolResultBudget`：工具结果超阈值（默认 8K 字符）**写盘 + 上下文仅留预览指针**；`SmartReActAgent` 在工具结果落点处 `ToolResultBudget.apply(...)` |
| **G4** 结构化 Handoff 文档 | ✅ | 新增 `HandoffContext`（摘要/必读文件/关键约束/关键数据）+ `HandoffCommand.fromFreeText/structured` 工厂；`contextPayload()` 返回结构化渲染文本，下游 `GraphExecutionService` 零改动；`HandoffCommandTest` 守护 |
| **G5** 压缩失败断路器 + 子代理深度上限 | ✅ | `SmartReActAgent`：连续压缩失败 ≥3 次打开**断路器**，暂停 LLM 压缩并降级为 `cheapTruncate` 确定性兜底；`GraphExecutionService`：`MAX_HANDOFF_DEPTH=5` 限制 Handoff 链深度（两处 while 循环） |
| **G6** Lost-in-the-Middle 重要性钉选 | ✅ | 新增 `ContextPinning`：压缩成功与截断兜底后 `pinSystemToFront(...)` 钉选系统指令到首位；`cheapTruncate` / `ProgressiveContextCompressor.snapOldTurns` 额外**保留首条用户请求**，防原始诉求被裁掉 |

**设计取舍说明**
- G2 未做文章描述的完整 4 档（写盘/Snip/MicroCompact/Collapse/AutoCompact），而是以「SNAP 廉价档 → LLM 全量档」两级实现核心价值（避免每次压缩都付 LLM 代价）；工具结果写盘（G3 第 0 级）已独立落地为 `ToolResultBudget`。
- G4 保留 `contextPayload()` 访问器语义（返回结构化渲染文本），使 `GraphExecutionService` 两个消费点**零改动**，降低回归风险。
- G5 断路器与既有 `DegradationService` 全链路熔断正交：前者专防「压缩链路自陷」，后者防「Agent 调用失败」，互不替代。

---

## 三、SmartAssistant 反超文章的几点（值得在面试中作为亮点）

1. **成本预算多租户化（L1/L2/L3）**：文章只讲单窗口 token 预算；SmartAssistant 有 per-global/app/user 三级**成本**预算 + 软降级模型，更贴近 SaaS 运营。
2. **后台预压缩（`precomputedCompactFuture`）**：压缩在后台提前算好，触发时不阻塞主循环——文章未强调的主动式优化。
3. **检索式记忆**：文章 Session Memory 是纯文本文件；SmartAssistant 把 9 段摘要向量化进 Milvus，可**按语义检索历史摘要**回填，信息召回更强。
4. **全链路错误率熔断**：文章仅在压缩失败处有断路器；SmartAssistant 有完整滑动窗口错误率熔断 + HALF_OPEN 探测恢复，覆盖整个 Agent 管线。
5. **失败分类恢复**：`ErrorType`（RETRYABLE/FATAL/NEED_REPLAN）让执行引擎差异化恢复，优于文章的通用 sub-agent 结果。

---

## 四、落地优先级建议（若排期）

| 优先级 | 项 | 工作量 | 收益 |
|---|---|---|---|
| P0 | G1 token 级窗口预算触发 | 中（改 `SmartReActAgent` 触发条件 + 加 token 计数） | 消除「撞硬上限崩溃/草草收尾」，直接对应文章核心痛点 |
| P0 | G3 工具结果预算 | 中（新增 `ToolResultBudget` + 消息区分） | G2 前提，避免大结果撑爆上下文 |
| P1 | G2 多级渐进压缩（0–3 级） | 高（新增 3 个压缩档 + 策略编排） | 显著降低 LLM 摘要调用成本与延迟 |
| P1 | G4 结构化 Handoff 文档 | 低（改 `HandoffCommand` 负载结构） | 长链路串行任务接手更稳 |
| P2 | G5 压缩失败断路器 + 子代理深度 | 低-中 | 防御性，避免自陷 |
| P2 | G6 重要性钉选 | 中 | 缓解 Lost-in-the-Middle |

---

## 五、面试回答要点（结合项目，可直接用）

> 「有限工作内存治理，我先分**可重取信息**（日志/搜索/工具结果）和**必留信息**（目标/约束/决策）。
> 在 SmartAssistant 里：先用 `TokenBudgetService` 做 L1/L2/L3 成本预算软降级；上下文用 `ContextOrchestrator` 四层预算拼接（状态锚点/短期/中期/RAG/问题）；历史靠 `ContextCompressor` 9 段式增量摘要 + 后台预压缩；长链路用 `HandoffCommand` 显式交接、`SubTaskResult` 把结果拆成 summary(入上下文)+result(按需查)、`GraphExecutionService` 做 DAG 编排；全链路有 `DegradationService` 错误率熔断兜底。
> 我下一步会补的是：把压缩触发从**消息数**改成 **token 窗口利用率**驱动（对齐 AUTOCOMPACT_BUFFER=13K 缓冲带），并加工具结果预算和多级渐进压缩，避免大工具结果直接撑爆窗口。」

---

*生成依据：文章全文抓取 + SmartAssistant 源码核验（`ContextCompressor` / `TokenBudgetService` / `CompressionHooks` / `ContextOrchestrator` / `ConversationSummarizationService` / `HandoffCommand` / `SubTaskResult` / `DegradationService` / `ConversationSummaryStore` / `SmartReActAgent` 触发逻辑）。所有差距结论均对照真实代码，非凭记忆。*
