# SmartAssistant 增量 PRD：工具网关安全与工程化加固

> **版本**：v1.0  
> **日期**：2026-07-10  
> **作者**：产品经理 许清楚  
> **状态**：待架构评审

---

## 1. 产品目标

| # | 目标 | 衡量标准 |
|---|------|----------|
| G1 | **安全可控** — 建立工具调用的确定性安全防护体系，确保不可用/高风险工具无法被误调用 | DISABLED/REMOVED 工具拦截率 100%；needsApproval=true 工具无审批不可执行 |
| G2 | **质量前置** — 在研发流程中前置质量与安全门禁，降低生产事故风险 | CI 安全扫描覆盖依赖漏洞+密钥泄露+Manifest 合规；破坏性工具变更在注册阶段检出 |
| G3 | **AI 协作增效** — 通过结构化项目上下文和能力声明，提升 AI 辅助开发与工具路由的准确性 | ai-project-context.md 自动注入系统提示词；Router 可按 capabilities 过滤工具 |

---

## 2. 用户故事

| # | 角色 | 故事 |
|---|------|------|
| US1 | 开发者 | 作为开发者，我希望 DISABLED/REMOVED 状态的工具被自动拦截，这样我不会在不知情的情况下调用了已下线的工具。 |
| US2 | 开发者 | 作为开发者，我希望退款、删除等高风险工具在执行前必须获得审批，这样关键的不可逆操作不会被 AI 自主触发。 |
| US3 | 运维工程师 | 作为运维工程师，我希望 CI 流水线自动扫描依赖漏洞和密钥泄露，这样安全问题在 PR 阶段就能被发现而不是等到生产。 |
| US4 | 开发者 | 作为开发者，我希望修改工具方法签名时系统能自动检测破坏性变更并要求版本号升级，这样接口兼容性不会被意外破坏。 |
| US5 | AI 助手 | 作为 AI 编码助手，我希望系统提示词中包含项目的技术栈、微服务清单和编码规范，这样我生成的代码能符合项目约定。 |

---

## 3. 需求池

### P0 — Must Have

#### REQ-01：ToolStatus 执行拦截

**背景**：`ToolGateway.execute()` 的 9 步执行链（第 0~9 步）中，步骤 0 仅检查工具是否存在（`def == null`），不检查 `ToolStatus`。ToolStatus 枚举已定义五阶段生命周期（EXPERIMENTAL→ACTIVE→DEPRECATED→DISABLED→REMOVED），但执行链从未读取此字段。

| 项 | 内容 |
|----|------|
| 优先级 | P0 |
| 预估工作量 | 0.5 天 |
| 影响范围 | `ToolGateway.execute()` |

**验收标准**：

- [ ] AC1：调用 `DISABLED` 状态工具时，抛出 `ToolExecutionException`，错误码为 `TOOL_EXECUTION_FAILED`，错误消息包含工具名和当前状态
- [ ] AC2：调用 `REMOVED` 状态工具时，抛出 `ToolExecutionException`，错误消息包含工具名和"工具已移除"
- [ ] AC3：调用 `DEPRECATED` 状态工具时，工具正常执行，但输出 WARN 级别日志，包含工具名、`deprecatedBy`（替代工具名，如有）和 `sunsetDate`
- [ ] AC4：调用 `ACTIVE` 和 `EXPERIMENTAL` 状态工具时，正常执行，无额外行为
- [ ] AC5：状态检查位于执行链步骤 0（工具存在性检查）之后、步骤 1（幂等检查）之前
- [ ] AC6：单元测试覆盖全部 5 种 ToolStatus 的调用路径

---

#### REQ-02：Pre/PostToolUse 确定性 Hook 机制

**背景**：`ToolGateway.execute()` 的 9 步执行链是硬编码的，没有扩展点。`ToolDefinition.needsApproval` 字段已存在（第 70 行），但执行链从未检查此字段。高风险工具（如退款 `refund_order`）标记了 `needsApproval=true` 却可被直接执行。

| 项 | 内容 |
|----|------|
| 优先级 | P0 |
| 预估工作量 | 2-3 天 |
| 影响范围 | `ToolGateway`、新增 `ToolExecutionHook` 接口及 4 个实现 |

**验收标准**：

- [ ] AC1：新增 `ToolExecutionHook` 接口，包含三个方法：
  - `preExecute(ToolDefinition def, String scope)` — 执行前拦截，返回 false 或抛异常可终止执行
  - `postExecute(ToolDefinition def, String result, long elapsedMs)` — 执行后处理
  - `onError(ToolDefinition def, Throwable error)` — 异常处理
- [ ] AC2：Hook 通过 Spring `@Component` + `@Order` 注入，按 order 值升序执行；`ToolGateway` 通过 `List<ToolExecutionHook>` 自动收集所有实现
- [ ] AC3：`preExecute` 返回 false 时，`ToolGateway` 抛出 `ToolExecutionException`，不执行后续步骤和工具逻辑
- [ ] AC4：Hook 执行点位于执行链中——`preExecute` 在步骤 0（工具存在性检查）之后、步骤 1（幂等检查）之前；`postExecute` 在步骤 6（审计日志）之后；`onError` 在 catch 块中、`recordFailure` 之前
- [ ] AC5：**ApprovalHook** — 当 `def.isNeedsApproval() == true` 时，`preExecute` 检查调用上下文中是否携带审批令牌（`ApprovalContext`）；未携带则抛出 `ApprovalRequiredException`，拒绝执行
- [ ] AC6：**ToolStatusHook** — 实现 REQ-01 的状态拦截逻辑（AC1~AC4），作为 `preExecute` 的一个 Hook 实现
- [ ] AC7：**AuditHook** — `postExecute` 记录结构化审计日志，包含字段：`toolName`、`scope`、`elapsedMs`、`resultLength`、`riskLevel`、`success=true`；`onError` 记录 `success=false` 及错误摘要
- [ ] AC8：**SanitizeHook** — `postExecute` 对返回结果执行正则脱敏：手机号（11 位）→ `138****1234`，身份证号（18 位）→ 前 6 后 4，银行卡号（16-19 位）→ 前 4 后 4
- [ ] AC9：现有执行链中的步骤 6（审计日志）可由 AuditHook 替代，避免重复日志；迁移后原步骤 6 移除
- [ ] AC10：单元测试覆盖每个 Hook 的独立行为；集成测试覆盖多 Hook 组合执行顺序

---

### P1 — Should Have

#### REQ-03：CI/CD 安全加固

**背景**：当前 `.github/workflows/eval-gate.yml` 仅包含 `compile-all`（编译检查）和 `eval-gate`（评测门禁）两个 Job，完全缺少安全扫描。

| 项 | 内容 |
|----|------|
| 优先级 | P1 |
| 预估工作量 | 2-3 天 |
| 影响范围 | `.github/workflows/eval-gate.yml`（或新增 workflow 文件） |

**验收标准**：

- [ ] AC1：新增 `security-scan` Job，与 `compile-all` 并行执行：
  - **依赖漏洞扫描**：使用 OWASP Dependency-Check（Maven 插件 `org.owasp:dependency-check-maven`），扫描全模块依赖；CRITICAL 级别漏洞（CVSS ≥ 9.0）阻断 PR 合并
  - **密钥泄露扫描**：使用 Gitleaks Action（`gitleaks/gitleaks-action`），扫描全量代码变更；检测到密钥模式（API Key、Token、私钥）时阻断 PR
  - **PR 标题注入扫描**：校验 PR 标题不含 shell 元字符（`` ` ``、`$`、`;`、`|`、`&`），防止 CI 脚本注入
- [ ] AC2：新增 `manifest-validate` Job（与 `compile-all` 并行）：
  - 校验所有 `@Tool` 注解方法的 `ToolDefinition` 注册点：`capabilities` 字段非空、`outputSchema`（如声明）为合法 JSON
  - 校验逻辑通过 Maven 测试执行（`-Dtest=ManifestValidationTest`），无需额外运行时
- [ ] AC3：安全扫描结果以 SARIF 格式上传至 GitHub Code Scanning（`github/codeql-action/upload-sarif`）
- [ ] AC4：`security-scan` 和 `manifest-validate` 在 GitHub Branch Protection 中设为 required status check
- [ ] AC5：扫描失败时，PR 评论中包含具体的漏洞/泄露/合规问题描述和修复建议

---

#### REQ-04：工具版本兼容性检查

**背景**：`RegistryService.register()`（第 61-75 行）在更新工具时仅记录版本变更日志（`version={}→{}`），不检查参数签名兼容性。开发者修改 `@Tool` 方法参数（删除必填参数、修改类型）不会触发任何警告。

| 项 | 内容 |
|----|------|
| 优先级 | P1 |
| 预估工作量 | 2-3 天 |
| 影响范围 | `RegistryService.register()`、新增 `ToolCompatibilityChecker` |

**验收标准**：

- [ ] AC1：新增 `ToolCompatibilityChecker` 服务，在 `RegistryService.register()` 更新已有工具时调用
- [ ] AC2：比对新旧版本的参数签名（通过反射 `@Tool` 方法或 `ToolDefinition` 中存储的参数元数据）：
  - **删除必填参数** → 标记为 BREAKING（破坏性变更）
  - **修改参数类型**（如 String→Integer） → 标记为 BREAKING
  - **新增可选参数**（有默认值） → 标记为 COMPATIBLE（兼容变更）
  - **新增必填参数** → 标记为 BREAKING
  - **删除可选参数** → 标记为 COMPATIBLE
- [ ] AC3：BREAKING 变更要求 major 版本号递增（如 1.2.0→2.0.0）；若版本号未做 major 升级，拒绝注册并抛出 `IncompatibleVersionException`，错误消息列出具体变更项
- [ ] AC4：COMPATIBLE 变更要求 minor 版本号递增；版本号未变但有参数变更时，输出 WARN 日志但不拒绝
- [ ] AC5：版本号完全未变且签名无变化时，正常更新（幂等注册）
- [ ] AC6：兼容性检查结果记录在版本变更日志中（`[RegistryService]` 日志增加 `compatibility=BREAKING|COMPATIBLE|NONE` 字段）
- [ ] AC7：单元测试覆盖全部 5 种参数变更场景 × 版本号合规/不合规组合

---

#### REQ-05：完善 ToolDefinition Manifest

**背景**：`ToolDefinition` 已有 17 个字段（8 原有 + 9 Phase 0 新增），但缺少 `capabilities`（能力声明）和 `outputSchema`（输出格式校验）字段。Router 无法按能力过滤工具，工具返回结果无结构校验。

| 项 | 内容 |
|----|------|
| 优先级 | P1 |
| 预估工作量 | 3-4 天 |
| 影响范围 | `ToolDefinition`、`RegistryService.register()`、`ToolGateway.execute()` |

**验收标准**：

- [ ] AC1：`ToolDefinition` 新增 `capabilities` 字段（`String[]` 类型），声明工具能力标签；预定义标签枚举：`read-only`、`mutate-state`、`network-call`、`payment`、`data-access`、`ai-inference`
- [ ] AC2：`ToolDefinition` 新增 `outputSchema` 字段（`String` 类型，JSON Schema 格式），描述工具返回值的结构
- [ ] AC3：`RegistryService.register()` 校验 `capabilities` 非空且每个标签在预定义枚举内；不合法则拒绝注册
- [ ] AC4：`RegistryService.register()` 校验 `outputSchema`（如非 null）为合法 JSON Schema（使用 `com.networknt:json-schema-validator` 解析校验）
- [ ] AC5：**向后兼容**：未声明 `capabilities` 的旧工具默认填充 `["unknown"]`；`outputSchema` 为 null 时不强制校验
- [ ] AC6：`ToolGateway.execute()` 在 `postExecute` 阶段（或通过 SanitizeHook 之后的新增 ValidationHook），当 `outputSchema` 非 null 时校验返回结果是否符合 schema；不符合则输出 WARN 日志（不阻断，仅告警）
- [ ] AC7：`RegistryService.query()` 支持按 `capabilities` 过滤（新增重载方法 `query(String[] tags, ToolStatus status, String namespace, String[] capabilities)`）
- [ ] AC8：所有静态工厂方法（`read`、`write`、`highRisk`）更新默认 `capabilities`：`read` → `["read-only"]`，`write` → `["mutate-state"]`，`highRisk` → `["mutate-state", "payment"]`（按场景）
- [ ] AC9：单元测试覆盖字段校验、向后兼容、schema 校验逻辑

---

### P2 — Nice to Have

#### REQ-06：AI 项目指令手册

**背景**：`PromptBuilder` 的三层结构（base → service → dynamic）中，base 层加载 `prompts/base-prompt.txt`（通用行为规范），不包含项目级事实（技术栈版本、微服务清单、编码规范、安全红线）。AI 助手（含 IDE 内 AI 和系统内 AI）缺乏项目上下文，生成的代码可能不符合项目约定。

| 项 | 内容 |
|----|------|
| 优先级 | P2 |
| 预估工作量 | 1-2 天 |
| 影响范围 | 新增 `ai-project-context.md`、`PromptBuilder` |

**验收标准**：

- [ ] AC1：在项目根目录创建 `ai-project-context.md`，包含以下章节：
  - **技术栈**：Java 21、Spring Boot 3.4.8、Spring AI、Maven 多模块
  - **微服务清单**：Router、Consumer、Order、Product、Tool Registry、Gateway、Embedding、Recommend、User — 每个服务一句话职责描述
  - **编码规范**：命名约定（snake_case 工具名、PascalCase 类名）、异常处理（统一 `ToolExecutionException` + `AgentErrorCode`）、日志规范（`[ClassName]` 前缀 + 结构化字段）
  - **安全红线**：禁止硬编码密钥（使用 `.env`）、禁止 `System.out`（使用 SLF4J）、SQL 必须参数化、`@Tool` 方法必须通过 `ToolGateway` 执行
  - **测试规范**：单元测试覆盖率 ≥ 80%，集成测试使用 Testcontainers
- [ ] AC2：`PromptBuilder` 新增 `withProjectContext()` 方法，从 classpath（`ai-project-context.md`）加载内容并作为 base 层与 service 层之间的独立层注入
- [ ] AC3：注入内容有 token 预算控制——通过字符数估算（1 token ≈ 4 字符），上限 2000 tokens（约 8000 字符）；超限时截断并追加 `[项目上下文已截断]` 提示
- [ ] AC4：`ai-project-context.md` 加载结果缓存（同 `basePrompt` 模式），避免重复 IO
- [ ] AC5：`PromptBuilder.assemble()` 输出顺序调整为：base → project-context → service → sections → dynamic
- [ ] AC6：文件不存在时降级处理——输出 WARN 日志，不阻断提示词组装

---

## 4. 待确认问题

| # | 问题 | 影响需求 | 建议决策方 |
|---|------|----------|------------|
| Q1 | **ApprovalHook 的审批交互模式**：是同步阻塞等待人工审批（需引入审批队列+回调机制），还是异步先拒绝+提示用户手动触发（更简单但体验割裂）？ | REQ-02 (AC5) | 架构师 |
| Q2 | **ToolStatus 状态流转自动化**：是否需要支持 DEPRECATED 超过 sunsetDate 后自动转为 DISABLED？当前 `deprecate()` 方法已记录 sunsetDate 但无定时检查机制。 | REQ-01 | 架构师 |
| Q3 | **CI 安全扫描漏洞阈值策略**：CRITICAL（CVSS ≥ 9.0）阻断是确定的，HIGH（7.0-8.9）级别是告警放行还是也阻断？阻断范围过大可能阻塞日常开发。 | REQ-03 (AC1) | 产品+运维 |
| Q4 | **@Deprecated 注解联动**：版本兼容性检查是否需要自动检测 Java `@Deprecated` 注解并关联到 ToolStatus.DEPRECATED 状态？还是两者独立维护？ | REQ-04 | 架构师 |
| Q5 | **outputSchema 运行时校验策略**：工具返回结果不符合 schema 时，是仅 WARN 告警（当前设计），还是应抛出异常阻断结果返回？告警更宽容但可能漏过数据质量问题。 | REQ-05 (AC6) | 架构师 |
| Q6 | **capabilities 标签体系扩展性**：预定义枚举（6 个标签）是否足够？是否需要支持自定义标签？自定义标签会降低 Router 过滤的确定性。 | REQ-05 (AC1) | 架构师 |
| Q7 | **ai-project-context.md 维护责任**：由产品经理维护（保证业务准确性），还是随代码 PR 一起更新（保证技术时效性）？建议后者，但需要在 PR 模板中增加检查项。 | REQ-06 | 产品+开发 |
| Q8 | **REQ-01 与 REQ-02 的实现关系**：REQ-01 的状态拦截逻辑应该直接内联在 ToolGateway 执行链中（简单直接），还是作为 ToolStatusHook 实现（架构统一但增加 REQ-02 依赖）？建议作为 Hook 实现，但需确认 REQ-02 先行或同步交付。 | REQ-01 + REQ-02 | 架构师 |

---

## 5. 优先级与交付计划

```
P0（Must Have）           P1（Should Have）         P2（Nice to Have）
┌──────────────────┐    ┌──────────────────────┐   ┌─────────────────┐
│ REQ-01 状态拦截   │    │ REQ-03 CI/CD 安全加固 │   │ REQ-06 AI 指令   │
│ REQ-02 Hook 机制  │    │ REQ-04 版本兼容性检查 │   │ 手册             │
└──────────────────┘    │ REQ-05 Manifest 完善  │   └─────────────────┘
     2.5-3.5 天          └──────────────────────┘
                          7-10 天                  1-2 天
```

**建议交付顺序**：
1. REQ-01 + REQ-02（P0，合并交付，因为 Q8 建议状态拦截作为 ToolStatusHook 实现）
2. REQ-05 Manifest 完善（P1，REQ-03 的 manifest-validate Job 依赖此字段）
3. REQ-04 版本兼容性检查（P1）
4. REQ-03 CI/CD 安全加固（P1，manifest-validate 部分依赖 REQ-05）
5. REQ-06 AI 指令手册（P2，独立可并行）

**总预估工作量**：10.5-18.5 天（1 人）
