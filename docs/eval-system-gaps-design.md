# 评测体系四大缺口落地设计（文章《Agent 评测体系》对照）

> 对应文章：《Agent 评测体系：从 demo 到生产的完整拆解》（梦朝思夕，2026-07-02，基于 Anthropic + 阿里工程化体系）
> 落地模块：`smart-assistant-common` / `com.example.smartassistant.common.eval`（含 `grader` 子包）
> 状态：代码已落地、`EvalGapsDesignTest` 10 例全绿、全模块编译无回归

---

## 0. 背景与差距

文章给出生产级 Agent 评测的「四层对象 + 五大维度 + Grader 三件套 + pass^k + 根因闭环」框架。
对照 SmartAssistant 既有评测体系，缺口如下：

| 缺口 | 文章要求 | SA 原状 | 本次落地 |
|------|---------|--------|---------|
| ① Trial / pass^k | 多次独立运行 + pass@k（至少一次成）/ pass^k（全成） | 单次 `AgentEvaluationResult.passed()` | `TrialRunner` + `PassKCalculator` |
| ② Grader 瀑布流 | 规则→LLM-Judge→人工 + partial credit + 偏差防护 | 仅规则（全有全无） | `grader` 包：Rule/Llm/GraderWaterfall |
| ③ 人工路由 | 低置信/边界升级 + 2% 抽检 | 全自动规则判分 | `HumanReviewRouter` + `HumanReviewStore` |
| ④ 根因分析 | 证据→收敛→诊断→定责→落盘 + 修复工单 | 有评测无诊断 | `RootCauseAnalyzer` |

---

## 1. 架构总览

```
                黄金测试集(AgentTestSpec)
                        │
          ┌─────────────▼─────────────┐
          │  ① TrialRunner ×N 次独立运行 │  ← TrialExecutor 注入实际 Agent 调用
          └─────────────┬─────────────┘
                        │ List<AgentEvaluationResult>
          ┌─────────────▼─────────────┐
          │  ② GraderWaterfall          │
          │    RuleGrader → (LlmGrader) │  ← 规则确定性优先，低置信升级 LLM
          └─────────────┬─────────────┘
                        │ GraderResult(含 partial / requiresHumanReview)
          ┌─────────────▼─────────────┐
          │  ① PassKCalculator          │  pass@k / pass^k 聚合
          └─────────────┬─────────────┘
                        │
          ┌─────────────▼─────────────┐
          │  ③ HumanReviewRouter        │  边界 ±0.1 / 低置信 / 2% 抽检 → 人工库
          └─────────────┬─────────────┘
                        │ 失败结果汇总
          ┌─────────────▼─────────────┐
          │  ④ RootCauseAnalyzer        │  聚类 → 诊断 → 定责 → 修复工单
          └────────────────────────────┘
```

编排入口：`EvalPipeline`（离线安全——未注入 `ChatModel` 时仅用 `RuleGrader`，纯内存可 CI）。

---

## 2. ① Trial / pass^k

**问题**：`AgentEvaluationResult.passed()` 是单次判定，无法回答「生产环境连续 10 次稳定性」。

**设计**：
- `TrialRunner`：对单个 `AgentTestSpec` 跑 N 次，每次通过 `TrialExecutor` 注入真实 Agent 调用（与框架解耦，便于 mock 测试）。
- `PassKCalculator`：给定观测通过率 p 与 k，解析估计：
  - `pass@k = 1 - (1-p)^k`（宽松，考察能否做成）
  - `pass^k = p^k`（严格，考察稳定做成，生产客服/支付最关心）
  - 另提供 `passAtKEmpirical`（枚举 C(n,k) 子集）做小样本交叉验证。

```java
TrialResult trial = new TrialRunner().run(spec, 10, executor);
PassKCalculator.PassKResult pk = PassKCalculator.from(trial, 10);
// pk.passAtK()  ≈ 1.0（十次过八次）；pk.passPowerK() ≈ 0.107（八次全成概率）
```

---

## 3. ② Grader 瀑布流（grader 子包）

**问题**：原 `computeScores()` 全确定性规则、非 0 即 1，无法评语义合理性，且会把「钻政策漏洞但实质正确」的回答错判失败。

**设计**（文章 Grader 三件套 + partial credit）：
- `Grader` 接口：`GraderResult grade(result, spec)`。
- `GraderResult`：含 `score / partial / rationale / graderType / confidence / requiresHumanReview`。
- `RuleGrader`：复用既有维度分；工具/关键词**部分命中**时 `partial=true`（避免全有全无）。
- `LlmGrader`：调 `ChatModel` 做语义评分，严格遵循文章 3 大偏差防护：
  - **长度偏差**：回复 >2000 字且高分 → 封顶 0.85；<10 字且高分 → 封顶 0.6。
  - **位置偏差**：prompt 不提供参考答案位置，避免盲从首条。
  - **自我偏好偏差**：prompt 强制「严格、挑刺」；无 `rationale` 时 `confidence` 强制降至 0.3 并标记需人工。
- `GraderWaterfall`：规则 → LLM 顺序升级，高置信即采用，控制 LLM 成本。

```java
List<Grader> graders = new ArrayList<>();
graders.add(new RuleGrader());
if (chatModel != null) graders.add(new LlmGrader(chatModel));
GraderWaterfall wf = new GraderWaterfall(graders);
```

---

## 4. ③ 人工路由

**问题**：全自动规则判分，无分层筛查与人工金标准，badcase 无出口。

**设计**（文章 2% 抽检 + 边界升级）：
- `HumanReviewTask`：复核任务（caseId / 分数 / 原因 / 状态 PENDING·APPROVED·REJECTED）。
- `HumanReviewStore` 接口 + `InMemoryHumanReviewStore`（分布式场景可换 Redis/PG）。
- `HumanReviewRouter.shouldRoute()` 命中任一即升级：
  1. `GraderResult.requiresHumanReview()`（低置信）；
  2. 分数落在阈值边界 ±0.1（模糊区）；
  3. 随机 `sampleRate`（默认 0.02）抽检。

```java
HumanReviewRouter router = new HumanReviewRouter(new InMemoryHumanReviewStore()); // 默认 2% 抽检
router.route(result, graderResult, spec); // 命中则入库，反哺 LLM-Judge 校准集
```

---

## 5. ④ 根因分析

**问题**：有评测无诊断，badcase 不回流。

**设计**（文章根因 5 步）：
- `FailureSignature`：从维度分 + 错误标志推导失败类型（WRONG_INTENT / WRONG_TOOLS / MISSING_KEYWORDS / ERROR / LOW_OVERALL）。
- `RootCauseAnalyzer.analyze()`：
  1. **证据**：收集 `!passed()` 的结果；
  2. **收敛**：按 `FailureType` 聚类；
  3. **诊断**：启发式表映射「失败类型 → 根因」；
  4. **定责**：标注 `RootCauseTag`（ALGORITHM / OPS / ENGINEERING / UNKNOWN）；
  5. **落盘**：写入 `RootCauseStore`，并 `generateFixTickets()` 产出「配置线/训练线/代码线」修复工单。

```java
RootCauseAnalysis rc = new RootCauseAnalyzer().analyze(allFailures);
List<String> tickets = new RootCauseAnalyzer().generateFixTickets(rc);
// 例：[代码线] 修复工具/Agent 逻辑 | 根因=工具 Schema 描述歧义 | 样本=[B] | 建议=收窄工具参数 enum
```

---

## 6. 集成：EvalPipeline

将四缺口串成链路，离线（ChatModel 可空）安全、与运行时解耦：

```java
EvalPipeline p = new EvalPipeline.Builder()
        .trials(10).k(10)
        .executor(spec -> liveAgentRunner.run(spec))   // 注入真实 Agent 调用
        .chatModel(chatModel)                          // 可空 → 仅规则打分
        .build();
List<EvalPipeline.CaseVerdict> vs = p.evaluateAll(specs);
RootCauseAnalysis rc = p.lastRootCause();              // 全量失败根因
```

**接入 CI / GoldenSuiteEvalGate（可选演进）**：在 `GoldenSuiteEvalGate.run()` 中，当 `enableAgentGate=true` 且注入 `TrialExecutor` 后，调用 `EvalPipeline` 替代当前的「待执行占位」，即可把 pass^k + 人工路由 + 根因分析纳入发布门禁。当前为保持既有 `GoldenSuiteEvalGateTest` 基线不变，未强制改写。

---

## 7. 关键文件清单

| 文件 | 缺口 | 说明 |
|------|------|------|
| `eval/TrialResult.java` | ① | 多轮 Trial 聚合结果 |
| `eval/PassKCalculator.java` | ① | pass@k / pass^k 计算（含 PassKResult） |
| `eval/TrialRunner.java` | ① | N 次独立执行器（TrialExecutor 注入） |
| `eval/grader/Grader.java` | ② | 打分器接口 |
| `eval/grader/GraderResult.java` | ② | 打分结果（partial / confidence / 人工标记） |
| `eval/grader/RuleGrader.java` | ② | 规则打分（部分得分） |
| `eval/grader/LlmGrader.java` | ② | LLM-Judge（3 大偏差防护） |
| `eval/grader/GraderWaterfall.java` | ② | 规则→LLM 瀑布流 |
| `eval/HumanReviewTask.java` | ③ | 人工复核任务 |
| `eval/HumanReviewStore.java` | ③ | 存储接口 |
| `eval/InMemoryHumanReviewStore.java` | ③ | 内存存储实现 |
| `eval/HumanReviewRouter.java` | ③ | 2% 抽检 + 边界升级路由 |
| `eval/RootCauseTag.java` | ④ | 责任角色枚举 |
| `eval/FailureSignature.java` | ④ | 失败签名（聚类键） |
| `eval/RootCauseAnalysis.java` | ④ | 分析结果（Diagnosis） |
| `eval/RootCauseAnalyzer.java` | ④ | 根因 5 步 + 修复工单 |
| `eval/EvalPipeline.java` | 集成 | 四缺口编排器 |
| `eval/EvalGapsDesignTest.java` | 验证 | 10 例单测，全绿 |

---

## 8. 验证结果

| 验证项 | 结果 |
|--------|------|
| `EvalGapsDesignTest` | `Tests run: 10, Failures: 0, Errors: 0` ✅ |
| 全模块 `mvn compile` | EXIT=0，无回归 ✅ |

10 例覆盖：pass^k 数学、TrialRunner 聚合、RuleGrader 部分得分、GraderWaterfall 离线、LlmGrader 解析 + 长度偏差封顶、HumanReviewRouter 抽检/跳过、RootCauseAnalyzer 聚类定责、EvalPipeline 全链路。

---

## 9. 后续演进（非阻塞）

1. **黄金集扩容**：10 → 50~200 条，补边界/对抗/多意图，规避「平衡坑」。
2. **LLM-Judge 校准集**：用 `HumanReviewTask` 沉淀的金标准反向校准 `LlmGrader` 阈值。
3. **接入 CI**：`GoldenSuiteEvalGate` 在 `enableAgentGate` 下调用 `EvalPipeline`，把 pass^k + 根因纳入发布门禁。
4. **Redis/PG 存储**：`HumanReviewStore` / `RootCauseStore` 换分布式实现，支持多人协作复核。
