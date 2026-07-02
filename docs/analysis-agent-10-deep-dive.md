# Agent 10 大深水题——SmartAssistant 项目对照分析报告

> 来源文章：《Agent 面试最容易挂的 10 个深水题》
> 分析日期：2026-07-02
> 分析方法：逐条阅读文章观点 → 对照项目代码 → 评估覆盖度 → 输出改进建议

---

## 一、执行摘要

对文章提出的 10 个核心问题逐一对照 SmartAssistant 项目代码，评估结论如下：

| 覆盖等级 | 数量 | 问题编号 |
|---------|------|---------|
| ✅ 已覆盖/超越 | 5 | Q1 ReAct局限、Q7 历史对话、Q8 Hybrid Search、Q9 Tool Description、Q10 工具路由 |
| ⚠️ 部分覆盖 | 2 | Q2 CoT vs Planning、Q5 推理断层、Q6 长期记忆 |
| ❌ 关键缺口 | 1 | Q3 鲁棒规划（错误分类） |
| 🟦 暂时不需要 | 1 | Q4 ToT/LATS |
| 📊 架构预留 | 1 | Q10 工具分层路由 |

**核心结论**：项目在 DAG 执行引擎、Hybrid Search、Tool Description 互斥、分层工具路由方面已超越文章水平。**Q3（鲁棒规划）** 是最关键缺口——SubTask 缺少错误分类和重规划触发机制。建议 3 个 P0 改进项投入约 2-3 天完成。

---

## 二、逐题对照分析

### Q1：ReAct 到底卡在哪里？

| 文章观点 | 项目现状 | 覆盖度 |
|---------|---------|--------|
| ReAct 有 6 局限：局部贪心、全局目标弱、状态不可控、错误传染、成本不稳、难验收 | 项目已超越纯 ReAct | ✅ 超越 |
| 三种升级路线：Plan-Then-Act / ReAct+轻规划 / Tree/Graph Planning | GraphExecutionService 实现了 DAG 分层并行执行 + Handoff 动态节点追加 | ✅ |
| 复杂任务需在外层加规划器、状态机或搜索机制 | TaskPlannerService 生成 id|描述|助理名|依赖 结构化计划 | ✅ |

**代码证据**：
- `GraphExecutionService.execute()` — 拓扑分层并行，60s 超时，死锁检测（连续2轮无进展终止）
- `TaskPlannerService.planToGraph()` — DAG 图结构任务分解
- `ExperienceModel` — REACT/TOOL 程序记忆辅助执行路径选择

**结论**：项目已完整走完 "ReAct → Plan-Then-Act → DAG 并行" 的升级路线，超前于文章讨论的层级。

---

### Q2：CoT 和 Planning 的本质区别是什么？

| 文章观点 | 项目现状 | 覆盖度 |
|---------|---------|--------|
| CoT 是"推理文本"，Planning 是"可执行任务表" | TaskPlannerService 输出结构化任务表 | ✅ |
| Planning 每项任务需含：id、action、依赖、验收标准 | SubTask 有 id/description/targetAgent/dependsOn | ⚠️ |
| 失败时精确到哪个任务失败并针对性恢复 | SubTaskResult.success 布尔值，无细化 | ⚠️ |
| **验收标准（successCriteria）是区分 Planning 和 CoT 的关键** | **SubTask 无 successCriteria 字段** | ❌ |

**缺口分析**：
```
SubTask 当前字段：
  ✅ id, description, targetAgent, dependsOn
  ❌ successCriteria（验收标准）—— "怎样算完成？"

影响：checker 无法判断"任务是否真的完成了目标"，只能依赖 Agent 自己返回的 success 布尔值
```

**改进建议**：SubTask 增加 `successCriteria` 字段（String，自由文本），让 TaskPlannerService 的 LLM 在规划时产出验收条件。ReflectionService 可基于此做更精准的 checker 校验。

**优先级**：P0（改动量小，影响面大）

---

### Q3：复杂工具任务怎么做鲁棒规划？

| 文章观点 | 项目现状 | 覆盖度 |
|---------|---------|--------|
| **任务状态机**：PENDING→RUNNING→SUCCEEDED/RETRYABLE_FAILED/FATAL_FAILED/NEED_REPLAN | AgentTaskStatus: PENDING/RUNNING/COMPLETED/FAILED/CANCELLED/TIMEOUT | ⚠️ |
| 区分可重试/不可重试错误 | SubTaskResult 只有 `boolean success` | ❌ |
| 精准回滚（按依赖图，只回滚受影响链路） | 失败节点后的依赖节点直接标 false，无智能回滚 | ❌ |
| 重规划触发（连续失败、关键输入缺失、产物不满足目标） | 连续2轮无进展终止（死锁检测），但不触发 replan | ❌ |
| 指数退避重试 | GraphExecutionService 层面无，Router 可能配置了 Resilience4j | ⚠️ |

**代码证据**：
```java
// AgentTaskStatus.java — 缺少 RETRYABLE_FAILED / FATAL_FAILED / NEED_REPLAN
public enum AgentTaskStatus {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED, TIMEOUT
}

// SubTaskResult.java — 只有 success 布尔值
public class SubTaskResult {
    private boolean success;  // ❌ 无法区分"网络超时可重试"vs"数据不存在不可重试"
}

// GraphExecutionService.java:121-127 — 异常直接标 false，无分类
.exceptionally(ex -> {
    completedMap.put(node.getId(), new SubTaskResult(
            node.getId(), node.getDescription(),
            node.getTargetAgent(), "", false));  // ❌ 所有异常一视同仁
});
```

**改进建议**：

1. **SubTaskResult 增加 errorType 字段**：
   ```java
   public enum ErrorType {
       NONE,                    // 无错误
       RETRYABLE_FAILED,        // 瞬时错误（网络超时/临时不可用）→ 重试
       FATAL_FAILED,            // 致命错误（数据不存在/权限不足）→ 跳过
       NEED_REPLAN              // 目标偏离/产物不合格 → 触发重规划
   }
   ```

2. **GraphExecutionService 增加错误分类逻辑**：根据异常类型设置 errorType，区分超时（RETRYABLE）vs 业务异常（FATAL）

3. **增加重规划触发**：当连续2个节点标记 NEED_REPLAN 时，重新调 TaskPlannerService 生成新计划

**优先级**：P0（改动量中，项目健壮性的关键提升）

---

### Q4：ToT / LATS 到底怎么工作？

| 文章观点 | 项目现状 | 覆盖度 |
|---------|---------|--------|
| ToT：生成多个中间 thought → 评估 → 保留 top-b → 展开 → 回溯 | 无 Tree-of-Thought 实现 | ❌ |
| LATS：蒙特卡洛树搜索（selection/expansion/simulation/backpropagation） | 无 Monte Carlo Tree Search 实现 | ❌ |
| 关键前提：必须有可靠评估器 | ReflectionService 五维评分可作为评估器基础 | ⚠️ |
| 适用场景：数学题、代码修复、多工具组合 | 当前业务（订单/商品客服）不需要多路径探索 | 🟦 |

**结论**：当前业务场景不需要 ToT/LATS。DAG 并行执行 + 意图路由 + Handoff 已满足客服场景需求。**暂不纳入改进计划**，若未来扩展到复杂推理场景（如金融分析、合规审查）再考虑。

---

### Q5：怎么解决推理断层和目标偏离？

| 文章观点 | 项目现状 | 覆盖度 |
|---------|---------|--------|
| 三层防线：提示工程 + 记忆机制 + 架构设计 | 项目有三层雏形 | ⚠️ |
| **独立 checker**：planner → executor → checker → replanner | ReflectionService 五维评分（规则+LLM-as-Judge） | ⚠️ |
| checker 只判断"目标是否满足、约束是否违反、是否需重规划" | ReflectionService 当前只判断"回复质量是否合格" | ⚠️ |
| 不要让同一个模型既执行又自夸 | ReflectionService 是独立服务（不同组件） | ✅ |

**代码证据**：
```java
// ReflectionService — 五维评分（规则，不调 LLM）
// 维度：长度(0.20) + 错误标记(0.25) + 关键词覆盖(0.25) + Agent健康(0.15) + 意图匹配(0.15)
// 阈值：0.60，不通过则换 Agent 重试（最多1次）

// RouterService — LLM-as-Judge 仅在边界分数(0.5~0.8)触发
QualityEvaluationResult quality = qualityEvaluationService.evaluate(...);
```

**差距**：

| 文章要求 | 项目实现 | 差距 |
|---------|---------|------|
| checker 判断目标是否满足 | ReflectionService 判断"质量"而非"目标达成" | 缺少 successCriteria 驱动的目标检查 |
| checker → replanner 闭环 | reflection → retry（换Agent），不是 replan | 无重规划机制 |
| 中间步骤 checker | 仅在最终结果触发 | 无执行过程中间检查 |

**改进建议**：
1. 配合 Q2 的 successCriteria，让 ReflectionService 增加"目标达成度"维度
2. 在 GraphExecutionService 每轮执行后增加 checker 节点

**优先级**：P1（依赖 Q2 的 successCriteria 落地）

---

### Q6：长期记忆模块怎么设计？

| 文章观点 | 项目现状 | 覆盖度 |
|---------|---------|--------|
| 三层存储：Event Log → Memory Store → Relation Graph | 部分覆盖 | ⚠️ |
| 四种记忆：工作/情景/语义/程序 | 四种记忆均有实现 | ✅ |
| **更新策略**：is_new_fact / is_update / is_conflict 三判断 | ExperienceModel 无冲突检测字段 | ❌ |
| 冲突时标记 supersedes/contradicts | InMemoryKnowledgeBase.removeSuperseded() 按版本号，非语义冲突 | ⚠️ |
| 时间衰减：降权/降噪/冷存储 | InMemoryKnowledgeBase 有 TIME_DECAY_LAMBDA，但 UserProfile 无 | ⚠️ |

**代码证据**：
```java
// ✅ 已实现：四种记忆体系
// 工作记忆 → ConversationService（Consumer 会话上下文）
// 情景记忆 → RoutingHistory（路由决策记录）
// 语义记忆 → ExperienceModel(COMMON) + UserProfile（偏好/意图分布）
// 程序记忆 → ExperienceModel(REACT/TOOL)（成功的工具调用/执行路径）

// ✅ 已实现：版本冲突检测
InMemoryKnowledgeBase.removeSuperseded() — 同 baseDocId 按 versionPriority 淘汰旧版本

// ❌ 缺失：语义冲突检测
// ExperienceModel 没有 isConflict / supersedes / contradicts 字段
// 两条"北京推荐景点"经验可能互相矛盾，但无法检测

// ⚠️ 缺失：UserProfile 时间衰减
// preferenceWeights 和 intentDistribution 只增不减
// 用户6个月前的"火锅偏好"权重 = 昨天刚表达的"日料偏好"权重
```

**改进建议**：

1. **ExperienceModel 增加冲突检测字段**：
   ```java
   private String supersedesId;     // 取代的经验 ID
   private String contradictsId;    // 矛盾的经验 ID
   private ConflictStatus conflictStatus; // NONE / RESOLVED_BY_NEWER / CONTRADICTED
   ```

2. **UserProfile 增加时间衰减**：在读取偏好时按最后更新时间降权
   ```java
   double weight = baseWeight * Math.exp(-DECAY_RATE * daysSinceUpdate);
   ```

3. **ExperienceService 增加 merge 逻辑**：新经验写入时先检索语义相似经验，判断是新事实/更新/冲突

**优先级**：
- 冲突检测字段 + UserProfile 衰减：P1
- 语义 merge 逻辑：P2

---

### Q7：历史对话太长，怎么不丢关键信息？

| 文章观点 | 项目现状 | 覆盖度 |
|---------|---------|--------|
| 五层组合：滑动窗口 + 工作记忆 + 分层摘要 + 向量检索 + event log | 项目有多层覆盖 | ✅ |
| 最近 N 轮原文保留 | Consumer 每10轮/1500 tokens触发压缩 | ✅ |
| 分层摘要（每个 summary 带来源回指） | RouterRagService 对历史生成 LLM 摘要 | ⚠️ |
| "摘要是加速层，不是事实源" | 符合——摘要失败回退到原始问题 | ✅ |

**代码证据**：
```java
// Consumer: 上下文压缩
// - 增量压缩，每10轮/1500 tokens触发

// RouterRagService.enhanceQuestion()
// - 判断是否需要RAG（代词/短问题/首次提问跳过）
// - LLM 生成上下文摘要 → 拼接到问题前
// - 失败时回退：返回原始问题
```

**差距**：缺少"分层摘要带来源回指"（每个摘要指向原始对话轮次），但当前场景影响不大。

**结论**：基本覆盖，无需立即改进。P2 可选：摘要增加 `sourceRoundRange` 回指字段。

---

### Q8：为什么工业 RAG 需要 Hybrid Search？

| 文章观点 | 项目现状 | 覆盖度 |
|---------|---------|--------|
| BM25：精确词/专有名词/SKU | Bm25Scorer（HanLP 分词，k1=1.5, b=0.75） | ✅ |
| Vector Search：语义相似/同义表达 | InMemoryKnowledgeBase + MilvusKnowledgeBase | ✅ |
| Reranker：最终排序 | ProductRagService DEEP 模式可选 rerank | ✅ |
| RRF 融合（推荐方案） | ProductRagService.rrfFuse()（RRF_K=60） | ✅ |
| 检索强度分级 | RetrievalProfile: LIGHT/STANDARD/DEEP | ✅ |

**代码证据**：
```java
// InMemoryKnowledgeBase.composeScore()
// 组合评分 = 余弦相似度 × 时间衰减 × 版本优先级 + BM25 × 混合权重

// ProductRagService — 5路召回
// Path 1: 精确匹配 → ProductBackend.queryProductInfo()
// Path 2: 关键词搜索 → ProductBackend.searchProduct()
// Path 3: BM25 语义评分 → Bm25Scorer
// Path 4: 经验知识 → KnowledgeQueryTool
// Path 5: Graph 图检索 → ProductGraphService
// → RRF 融合 → Top-K
```

**结论**：**完全覆盖且实现质量高**。项目在 Hybrid Search 方面的实现已超越文章描述的水平（文章讲的是理论，项目有完整的 5 路召回 + RRF 融合 + 检索强度分级的生产级实现）。

---

### Q9：Tool Schema 的 description 怎么写才有用？

| 文章观点 | 项目现状 | 覆盖度 |
|---------|---------|--------|
| 每个 tool 必须有：适用场景/禁止场景/前置条件/参数格式/相邻工具区分 | GeneralTools / OrderTools / CouponTools 均完整覆盖 | ✅ |
| description 是工具路由策略，不是注释 | 项目工具描述均有精确的互斥边界 | ✅ |
| 负向约束同等重要 | 每个工具都有"不适用场景" | ✅ |

**结论**：**已覆盖**（第一篇文章详细分析过）。

---

### Q10：工具很多时，怎么做工具选择？

| 文章观点 | 项目现状 | 覆盖度 |
|---------|---------|--------|
| 五层架构：目录→召回→选择→校验→评测 | 项目已有分层设计（按模块分组+意图路由） | ✅ |
| BM25 + Vector 对工具建索引 | 未实现（工具数< 30） | 🟦 |
| 执行前 Schema 校验 | ApprovalService 二阶段确认（敏感操作） | ⚠️ |
| 监控 Tool Selection Accuracy | 无 | ⚠️ |

**架构分析**：项目采用 **意图路由 → 子 Agent 工具集** 的模式，每个 Agent 只暴露自己的工具（如 Order Agent 只有 OrderTools + CouponTools），天然避免了"100个工具全塞进上下文"的问题。

**结论**：当前工具数< 30，不需要工具索引。但可预留：
- 工具执行监控（Tool Selection Accuracy / Argument Valid Rate）
- 非敏感工具的执行前参数校验（敏感工具已有 ApprovalService）

---

## 三、改进建议优先级排序

| 优先级 | 编号 | 改进项 | 涉及模块 | 预估工作量 | 依赖 |
|-------|------|--------|---------|-----------|------|
| **P0** | Q3.1 | SubTaskResult 增加 ErrorType 枚举（RETRYABLE_FAILED/FATAL_FAILED/NEED_REPLAN） | Router | 0.5天 | 无 |
| **P0** | Q2 | SubTask 增加 successCriteria 字段 + TaskPlannerService 产出验收标准 | Router | 0.5天 | 无 |
| **P0** | Q6.1 | ExperienceModel 增加 isConflict/supersedes/contradicts 冲突检测字段 | Router | 0.5天 | 无 |
| **P1** | Q3.2 | GraphExecutionService 增加错误分类逻辑（区分超时/业务异常）+ 指数退避重试 | Router | 1天 | Q3.1 |
| **P1** | Q3.3 | GraphExecutionService 增加重规划触发（NEED_REPLAN → 重新 TaskPlanning） | Router | 1天 | Q3.1 + Q2 |
| **P1** | Q5 | ReflectionService 增加 successCriteria 驱动的目标达成度检查 | Router | 0.5天 | Q2 |
| **P1** | Q6.2 | UserProfile 增加时间衰减（preferenceWeights / intentDistribution 降权） | Consumer | 0.5天 | 无 |
| **P2** | Q6.3 | ExperienceService 增加语义 merge 逻辑（新经验写入时判断新/更新/冲突） | Router | 1天 | Q6.1 |
| **P2** | Q7 | RouterRagService 摘要增加 sourceRoundRange 回指字段 | Router | 0.5天 | 无 |
| **P2** | Q10 | 工具执行监控指标（Tool Selection Accuracy / Argument Valid Rate） | Router + Monitoring | 1天 | 无 |
| 🟦 | Q4 | ToT/LATS 树搜索 | — | 暂不实施 | 当前不需要 |
| 🟦 | Q10.2 | 工具 BM25+向量索引 | — | 暂不实施 | 工具数 < 50 |

---

## 四、实施建议

### 第一阶段（P0，约 1.5 天）

**目标**：补齐最关键的三个结构性缺口。

1. **SubTask + SubTaskResult 增强**（同时改 Q2 + Q3.1）：
   - `SubTask` 增加 `successCriteria: String`
   - `SubTaskResult` 增加 `errorType: ErrorType`（NONE/RETRYABLE_FAILED/FATAL_FAILED/NEED_REPLAN）
   - `TaskPlannerService` 的 prompt 增加"为每个子任务生成验收标准"指令
   - `GraphExecutionService.executeNode()` 异常处理改为按异常类型分类设置 errorType

2. **ExperienceModel 冲突检测**（Q6.1）：
   - `ExperienceModel` 增加 `supersedesId`, `contradictsId`, `conflictStatus`
   - 不影响现有逻辑，纯粹是 schema 扩展

### 第二阶段（P1，约 3 天）

**目标**：增强鲁棒性和记忆质量。

3. **GraphExecutionService 错误分类 + 重试**（Q3.2）：
   - 超时异常 → RETRYABLE_FAILED，指数退避 `[1s, 3s, 10s]` 重试
   - 业务异常（ORDER_NOT_FOUND 等）→ FATAL_FAILED，标记依赖节点为跳过
   - 空结果/质量不合格 → NEED_REPLAN，触发重规划

4. **GraphExecutionService 重规划**（Q3.3）：
   - 连续2个节点 NEED_REPLAN → 重新调 TaskPlannerService
   - 第二次规划传入失败节点的上下文

5. **ReflectionService 目标检查**（Q5）：
   - 增加 successCriteria 匹配维度（简单规则：successCriteria 中的关键词是否在回复中出现）

6. **UserProfile 时间衰减**（Q6.2）：
   - preferenceWeights / intentDistribution 按 `lastUpdatedAt` 降权

### 第三阶段（P2，约 2.5 天，可选）

7. 语义 merge、摘要回指、监控指标——非紧迫，可在后续迭代中逐步完善。

---

## 五、附录：关键代码文件索引

| 文件 | 相关题目 |
|------|---------|
| `router/.../model/SubTask.java` | Q2, Q3 |
| `router/.../model/SubTaskResult.java` | Q2, Q3 |
| `router/.../service/core/GraphExecutionService.java` | Q1, Q3, Q5 |
| `router/.../service/core/TaskPlannerService.java` | Q1, Q2 |
| `router/.../service/core/ReflectionService.java` | Q5 |
| `router/.../service/experience/ExperienceModel.java` | Q6 |
| `common/.../scheduler/AgentTaskStatus.java` | Q3 |
| `common/.../rag/Bm25Scorer.java` | Q8 |
| `common/.../rag/InMemoryKnowledgeBase.java` | Q6, Q8 |
| `common/.../rag/retrieval/RetrievalProfile.java` | Q8 |
| `product/.../service/search/ProductRagService.java` | Q8 |
| `consumer/.../entity/UserProfile.java` | Q6 |
| `general/.../tool/GeneralTools.java` | Q9 |
| `order/.../tools/OrderTools.java` | Q9 |
| `order/.../tools/CouponTools.java` | Q9 |
| `router/.../service/rag/RouterRagService.java` | Q7 |
