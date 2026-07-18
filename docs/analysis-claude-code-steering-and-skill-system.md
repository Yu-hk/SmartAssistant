# SmartAssistant 借鉴方向分析：Claude Code 配置体系 & Skill 系统工程化（2026-07-10 修订版）

> 分析日期：2026-07-10（基于 git pull 后最新代码）
> 参考文章：
> - 《Steering Claude Code》— 程序员鱼皮（解读 Anthropic 官方博文）
> - 《Skills / Plugins / Agents 技能系统工程化》— 计算机魔术师
>
> **修订说明**：本文替代旧版分析。旧版写于 git pull 前，将多项已实现能力标记为"缺失"。本次基于最新源码全面修订。

---

## TL;DR

SmartAssistant 在最新代码中**已经覆盖了两篇文章中大部分最佳实践**——确定性安全护栏、Prompt 注入防护、评测闭环 + CI 门禁、基线回归（能力漂移检测）、Agent 版本协商、分层 Prompt 管理、阶段门控等均已落地。剩余 **6 个值得投入的方向**集中在工具层（Pre/PostToolUse Hook、Manifest 完整性、版本治理）和 CI/CD 安全加固层面。

---

## 一、已有能力对照（两篇文章 vs 项目现状）

### 1.1 《Steering Claude Code》七大配置方式对照

| 配置方式 | 文章核心思想 | SmartAssistant 对应实现 | 评估 |
|---------|-------------|------------------------|------|
| CLAUDE.md | 项目"事实"常驻，≤200行，只放事实不放流程 | ❌ 无等价物。README.md 面向人类开发者，非面向 AI 的结构化指令 | 🔴 缺失 |
| Rules | 路径级精准约束，按模块加载 | ✅ `ReActProfile` 按入口（order/general/product/mcp）分级配置；`GuardrailProperties` 按意图关键词约束 | 🟢 已有 |
| Skills | 按需加载的工作流，渐进式披露 | ✅ `PromptManager` 文件化加载 + 缓存；`SkillRepository` 从 YAML 加载 + Nacos 热刷新；`PromptBuilder` 三层组装 | 🟢 已有 |
| Subagents | 隔离上下文，只返回最终结果 | 🟡 多 Agent 路由（Router→Product/Order/General）有隔离；但单 Agent 内重型任务（多路 RAG、SQL 生成）未做子上下文隔离 | 🟡 部分 |
| Hooks | 确定性自动化，不依赖 AI 遵循指令 | 🟡 见下表详细分析 | 🟡 部分 |
| Output Styles | 输出风格定义 | N/A（Java 项目不直接对应） | — |
| Append System Prompt | 临时追加指令 | ✅ `PreALGate` 每轮注入结构化执行契约 | 🟢 已有 |

#### Hook 机制详细对照

| Hook 类型 | 文章描述 | SmartAssistant 现状 | 评估 |
|-----------|---------|-------------------|------|
| 请求前置拦截 | PreToolUse — 工具调用前校验 | ✅ `SafeGuardAdvisor`(Order=50) 确定性检测 Prompt 注入，命中即抛 `PromptInjectionBlockedException` | 🟢 已有 |
| 请求前置约束 | PreToolUse — 权限/参数校验 | ✅ `GuardrailService` 关键词强制路由 + 情绪分级干预 | 🟢 已有 |
| LLM 输出后分析 | PostToolUse — 结果处理 | ✅ `LoopGuardService` 正则检测基础设施错误/阻塞/用户决策请求 | 🟢 已有 |
| 阶段验收 | 确定性代码否决 LLM | ✅ `PhaseGate` 4 种纯代码检查（FILE_EXISTS/GLOB_COUNT/SCRIPT_CHECK/USER_CONFIRMATION） | 🟢 已有 |
| LLM 调用前注入 | 每轮执行契约 | ✅ `PreALGate` 确定性注入迭代计数 + 硬规则 | 🟢 已有 |
| 上下文压缩 | PreCompact/PostCompact | ✅ `CompressionHooks` 接口（beforeCompress/afterCompress） | 🟢 已有 |
| **工具调用前拦截** | PreToolUse — 审批/权限 | ❌ `ToolGateway` 无 Hook 接口；`needsApproval` 标志存在但**从未被检查** | 🔴 缺失 |
| **工具调用后处理** | PostToolUse — 结果脱敏/审计 | ❌ 无通用后置处理接口（仅有错误码检测和输出截断） | 🔴 缺失 |

### 1.2 《Skill 系统工程化》三大核心议题对照

#### 议题一：三层能力模型（Skill / Plugin / Agent）

| 层级 | 文章定义 | SmartAssistant 对应 | 评估 |
|------|---------|-------------------|------|
| Skill | 原子能力封装，独立可测试，最小权限 | `@Tool` 方法 + `ToolDefinition` + `SkillDefinition`(含 SkillSlot) | 🟢 概念对应 |
| Plugin | 多 Skill 编排 + 权限聚合 | `skill-packages.yml`（商品比价/库存预警/知识问答，含 version 字段） | 🟢 概念对应 |
| Agent | 运行时大脑 + 调度策略 | `SmartReActAgent` + 10 级优先级决策状态机 + `ReActProfile` 分级配置 | 🟢 完整 |

#### 议题二：Manifest 规范

| Manifest 要素 | 文章要求 | SmartAssistant 现状 | 评估 |
|--------------|---------|-------------------|------|
| name | 唯一标识 | ✅ `ToolDefinition.name` | 🟢 |
| description | 能力描述 | ✅ `ToolDefinition.description` | 🟢 |
| version | 语义版本号 | ✅ `ToolDefinition.version`（默认 "1.0.0"） | 🟢 字段存在 |
| capabilities | 声明式能力范围 | ❌ ToolDefinition 无此字段；Agent 层有 `AgentMetadata.capabilities` | 🔴 缺失 |
| permissions | 最小权限声明 | 🟡 `scopes[]` + `tags[]` 双层鉴权，但"或"关系放行；`needsApproval` 未实现 | 🟡 部分 |
| input schema | 类型安全 | 🟡 `SkillDefinition.SkillSlot` 有（name/type/required/default）；`@Tool` 方法签名隐式生成 JSON Schema | 🟡 部分 |
| output schema | 返回格式校验 | ❌ 工具统一返回 `String`，无结构化 output schema | 🔴 缺失 |
| status | 生命周期 | ✅ `ToolStatus` 五阶段（EXPERIMENTAL→ACTIVE→DEPRECATED→DISABLED→REMOVED） | 🟢 设计完整 |
| riskLevel | 风险分级 | ✅ `ToolRiskLevel` 四级（READ/LOW/MEDIUM/HIGH） | 🟢 |
| timeout | 执行超时 | ✅ `ToolDefinition.timeout`（默认 10s） | 🟢 |
| rateLimit | 限流 | ✅ `ToolDefinition.rateLimit` | 🟢 |
| ownerTeam | 归属团队 | ✅ `ToolDefinition.ownerTeam` | 🟢 |

#### 议题三：版本治理

| 治理要素 | 文章要求 | SmartAssistant 现状 | 评估 |
|---------|---------|-------------------|------|
| 语义版本号 | 所有 Skill 遵循 semver | 🟡 `ToolDefinition.version` 存在但注册时**无兼容性检查**；`AgentMetadata` 有完整版本协商 | 🟡 部分 |
| Schema 兼容性 | 新增可选→minor，删除必填→major | ❌ `RegistryService.register()` 仅记录版本变更日志，不检查兼容性 | 🔴 缺失 |
| 能力漂移检测 | 声明-实现一致性校验 | 🟡 `EvalBaseline` + `maxRegression=0.05` 做系统级质量漂移检测；`pass^k` 稳定性判定做"运气漂移"检测；但无工具级行为漂移检测 | 🟡 部分 |
| 废弃流程 | 提前一个 major 版本通知 | 🟡 `ToolStatus.DEPRECATED` + `deprecatedBy` + `sunsetDate` 字段存在；`RegistryService.deprecate()` 可设置废弃状态；但无自动通知机制 | 🟡 部分 |
| 版本锁定 | lockfile vs 实时注册 | 🟡 `ToolRegistryClient` 本地缓存 + TTL（30s）+ 降级策略；混合模式但无 lockfile | 🟡 部分 |

### 1.3 安全防线对照

| 安全维度 | 文章要求 | SmartAssistant 现状 | 评估 |
|---------|---------|-------------------|------|
| Prompt 注入防护 | Hook + 权限双保险 | ✅ `SafeGuardAdvisor` 关键词+正则双层确定性检测 + `PromptInjectionBlockedException` 拦截 | 🟢 已实现 |
| 情绪危机干预 | — | ✅ `GuardrailService` 4 级情绪检测（HEAVY→禁用工具+心理热线） | 🟢 超出预期 |
| 关键词强制路由 | — | ✅ `GuardrailService` 退款/退货/投诉等关键词强制 skipShortCircuit + forceRag | 🟢 已实现 |
| 循环防护 | — | ✅ 5 层防死循环（迭代上限/超时/Token预算/FNV哈希去重/无进展检测） | 🟢 已实现 |
| Token 预算控制 | — | ✅ `AgentSafetyService` 80% 上下文窗口阈值 | 🟢 已实现 |
| **工具调用前权限校验** | PreToolUse Hook | ❌ `ToolGateway` 鉴权是 Scope+Tag 检查，但 `needsApproval` 标志**未被检查**；无审批拦截 | 🔴 缺失 |
| **PR 标题注入扫描** | CI 层防护 | ❌ CI 仅有编译检查 + 评测门禁 | 🔴 缺失 |

---

## 二、仍值得借鉴的 6 个改进方向

基于以上对照分析，以下是**在最新代码基础上仍存在明确差距**的改进方向，按优先级排序。

---

### 🔴 P0：方向一 — PreToolUse / PostToolUse 确定性 Hook

#### 问题

`ToolGateway.execute()` 是所有工具调用的统一入口（9 步执行链），但其中**没有 Hook 扩展点**：

1. `needsApproval` 标志存在于 `ToolDefinition` 中，但 `ToolGateway` **从未检查该标志**——高风险工具（如退款、删除）标记了 `needsApproval=true` 却可以被执行
2. 工具执行前无法做参数校验、审批拦截、沙箱检查
3. 工具执行后无法做统一的结果脱敏、审计增强、指标采集

这正是两篇文章共同强调的核心观点：**"永远不要做 X"靠指令是不够的，需要确定性代码保障**。

#### 建议方案

在 `ToolGateway` 中增加 Hook 接口：

```java
public interface ToolExecutionHook {
    /** 工具执行前 — 返回 false 拦截执行 */
    boolean preExecute(ToolInvocation invocation);

    /** 工具执行后 — 可修改返回结果 */
    String postExecute(ToolInvocation invocation, String result);

    /** 执行异常时 */
    default void onError(ToolInvocation invocation, Throwable error) {}
}
```

在 `ToolGateway.execute()` 的第 1 步（获取定义后）和第 6 步（执行后）插入 Hook 调用。

#### 关键 Hook 实现

| Hook | 优先级 | 职责 |
|------|--------|------|
| `ApprovalHook` | 最高 | 检查 `needsApproval=true` 的工具，若当前会话无审批 token 则拦截 |
| `PermissionHook` | 高 | 校验当前会话的 Scope/Tag 是否覆盖工具所需权限 |
| `ToolStatusHook` | 高 | 拦截 `DISABLED`/`REMOVED` 状态的工具调用 |
| `AuditHook` | 中 | 记录工具调用的完整审计日志（调用方、参数、结果、耗时） |
| `SanitizeHook` | 中 | 对工具返回结果做敏感信息脱敏（手机号、身份证等） |

#### 预估工作量

2-3 天（接口定义 + 5 个默认实现 + ToolGateway 改造 + 单测）

---

### 🔴 P0：方向二 — ToolStatus 执行拦截

#### 问题

`ToolStatus` 定义了完整的五阶段生命周期，但 `ToolGateway.execute()` **不检查 status**：

```java
// 当前代码：获取定义后直接进入鉴权，不检查 status
ToolDefinition def = toolRegistry.get(toolName);
if (def == null) throw new ToolExecutionException(...);
// 直接进入第 1 步幂等检查... ← 这里应该先检查 status
```

这意味着 `DEPRECATED` 的工具会正常执行（只是健康检查标记为 DEGRADED），`DISABLED` 的工具也能被调用。只有 `RegistryService.getHealth()` 在健康检查时才区分这些状态。

#### 建议方案

在 `ToolGateway.execute()` 第 0 步之后增加 status 检查：

```java
ToolDefinition def = toolRegistry.get(toolName);
if (def == null) throw new ToolExecutionException(...);

// 新增：生命周期状态检查
switch (def.getStatus()) {
    case DISABLED, REMOVED -> throw new ToolExecutionException(
        "工具 " + toolName + " 当前状态为 " + def.getStatus() + "，不可调用",
        AgentErrorCode.TOOL_UNAVAILABLE);
    case DEPRECATED -> log.warn("工具 {} 已废弃（替代: {}，下线日期: {}），本次仍放行",
        toolName, def.getDeprecatedBy(), def.getSunsetDate());
    case EXPERIMENTAL -> log.debug("工具 {} 处于实验期", toolName);
    case ACTIVE -> { /* 正常 */ }
}
```

#### 预估工作量

0.5 天（极小改动，但安全收益巨大）

---

### 🟡 P1：方向三 — 完善 ToolDefinition Manifest（capabilities + output schema）

#### 问题

当前 `ToolDefinition` 已有 17 个字段（包括 version、status、tags、namespace、ownerTeam 等），但缺少两个关键 Manifest 要素：

1. **capabilities**：无法声明式描述工具的能力范围。Agent 只能通过 `description` 字段的自然语言猜测工具能做什么，无法做精确的能力匹配
2. **output schema**：工具统一返回 `String`，调用方无法对返回值做结构化校验。如果工具返回了不符合预期的格式，错误要到下游处理时才暴露

#### 建议方案

```java
// ToolDefinition 新增字段
private String[] capabilities;        // 能力声明 ["query_product", "compare_price"]
private JsonSchema outputSchema;      // 输出 JSON Schema（可选，null 表示不校验）
private JsonSchema inputSchema;       // 显式输入 Schema（与 @Tool 方法签名双校验）
```

`RegistryService.register()` 时校验：
- inputSchema 与 `@Tool` 方法签名推导的 Schema 是否一致（检测能力漂移）
- outputSchema 非空时，工具返回值做 JSON Schema 校验

#### 预估工作量

3-4 天（POJO 定义 + Schema 校验器 + 注册时校验 + 3-5 个工具的 Manifest 补全 + 单测）

---

### 🟡 P1：方向四 — CI/CD 安全加固

#### 问题

当前 `.github/workflows/eval-gate.yml` 有 2 个 Job：
- `compile-all`：全模块编译 ✅
- `eval-gate`：黄金集评测门禁 ✅

但**完全缺少安全检查环节**：

| 缺失的 CI 检查 | 风险 |
|---------------|------|
| 依赖安全扫描（OWASP Dependency-Check / Trivy） | 第三方依赖 CVE 漏洞不可见 |
| 密钥泄露检测（GitLeaks / TruffleHog） | API key / 数据库密码可能被提交到仓库 |
| PR 标题/Commit Message 注入扫描 | 恶意指令通过 PR 标题注入 CI Agent |
| Manifest 合规性校验 | 工具注册时无 Schema/权限/版本规范检查 |
| 镜像安全扫描 | Docker 镜像中的漏洞不可见 |

这正是第二篇文章 4.2 节强调的"CI/CD 流水线应该包含的关键环节"。

#### 建议方案

在 `eval-gate.yml` 中新增 3 个 Job：

```yaml
security-scan:
  name: Security scan
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    # 依赖安全扫描
    - name: OWASP Dependency Check
      run: mvn -B org.owasp:dependency-check-maven:check
    # 密钥泄露检测
    - uses: gitleaks/gitleaks-action@v2
    # PR 标题注入扫描
    - name: Prompt injection scan
      run: |
        # 检查 PR 标题/commit message 是否包含可疑指令模式
        python3 scripts/scan-prompt-injection.py "${{ github.event.pull_request.title }}"

manifest-validate:
  name: Manifest validation
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - name: Validate tool manifests
      run: |
        # 校验所有 manifest.yaml 的 Schema 合规性
        # 校验版本号遵循 semver
        # 校验 permissions 声明完整性
        python3 scripts/validate-manifests.py
```

#### 预估工作量

2-3 天（Workflow 编写 + 扫描脚本 + 调优误报）

---

### 🟡 P1：方向五 — 工具层版本兼容性检查

#### 问题

Agent 层有完整的版本管理（`AgentMetadata` + `AgentVersionNegotiator`），但工具层**有版本号无检查**：

```java
// RegistryService.register() — 仅记录日志，不检查兼容性
if (existing != null) {
    log.info("更新工具: name={}, version={}→{}, status={}→{}",
            definition.getName(), existing.getVersion(),
            definition.getVersion(), existing.getStatus(), definition.getStatus());
}
```

如果开发者修改了 `@Tool` 方法的参数签名（删除必填参数、修改参数类型），注册时不会发出任何警告，调用方在运行时才会遇到错误。

#### 建议方案

在 `RegistryService.register()` 中增加版本兼容性检查：

```java
public boolean register(ToolDefinition definition) {
    ToolDefinition existing = definitions.get(definition.getName());
    if (existing != null && existing.getVersion() != null) {
        CompatibilityResult compat = checkCompatibility(existing, definition);
        if (compat.isBreaking()) {
            log.error("检测到破坏性变更: tool={}, {} → {}, 原因: {}",
                definition.getName(), existing.getVersion(),
                definition.getVersion(), compat.reason());
            // 破坏性变更需要 major 版本号升级
            if (!isMajorBump(existing.getVersion(), definition.getVersion())) {
                throw new IncompatibleVersionException(
                    "破坏性变更必须升级 major 版本号: " + compat.reason());
            }
        }
    }
    // ... 正常注册
}
```

兼容性检查维度：
- **参数删除**：必填参数被删除 → 破坏性
- **参数类型变更**：String → Integer → 破坏性
- **参数语义变更**：需要人工标注（如温度单位变化）→ 标记为需 review
- **新增可选参数** → 兼容（minor 版本）
- **新增必填参数** → 破坏性（除非有默认值）

#### 预估工作量

2-3 天（兼容性检查器 + 注册流程改造 + 单测）

---

### 🟢 P2：方向六 — 面向 AI 的项目指令手册（CLAUDE.md 等价物）

#### 问题

项目没有面向 AI 的结构化指令手册。当前的 `base-prompt.txt` 是通用行为规范，但不含项目级"事实"（构建命令、目录结构、技术栈、编码规范、团队约定）。

两篇文章都强调的核心理念：**CLAUDE.md 只放"事实"不放"流程"，≤200 行**。SmartAssistant 的"事实"目前散落在 README.md（面向人类）、各模块 application.yml、Java 注解和代码注释中。

#### 建议方案

创建 `smart-assistant-common/src/main/resources/ai-project-context.md`（或通过 Nacos 配置），包含：

```markdown
# SmartAssistant AI 项目上下文

## 技术栈
- Java 21 + Spring Boot 3.4.8 + Spring AI 2.0.0
- PostgreSQL + Milvus + Redis + Nacos
- React 18 + Vite + MUI + Tailwind CSS

## 微服务清单
| 服务 | 端口 | 职责 |
|------|------|------|
| Router | 8080 | 路由分发 + 语义缓存 + 经验匹配 |
| Consumer | 8081 | 用户对话入口 + SSE 推送 |
| Order | 8082 | 订单 Agent + 售后/退款 |
| Product | 8083 | 商品 Agent + RAG 检索 |
| Tool Registry | 8090 | 工具注册中心 |

## 编码规范
- 工具定义使用 @Tool 注解 + ToolDefinition.builder()
- Prompt 文件化管理（prompts/ 目录），禁止硬编码
- 所有工具调用走 ToolGateway，禁止直接调用
- 高风险工具（HIGH）必须设置 needsApproval=true

## 安全红线
- 禁止在 Prompt 中包含数据库连接信息
- 禁止工具返回原始用户密码/Token
- SQL 工具仅允许 SELECT，禁止 DDL/DML
```

通过 `PromptBuilder` 在构建 System Prompt 时自动注入此文件，使所有 Agent 共享统一的项目上下文。

#### 预估工作量

1-2 天（文档编写 + PromptBuilder 集成 + 验证）

---

## 三、优先级总览

| 优先级 | 改进方向 | 预估工作量 | 核心收益 |
|--------|---------|-----------|---------|
| 🔴 P0 | 方向二：ToolStatus 执行拦截 | 0.5 天 | 极小改动，防止已下线工具被调用 |
| 🔴 P0 | 方向一：Pre/PostToolUse Hook | 2-3 天 | 填补工具调用安全缺口，实现审批拦截 |
| 🟡 P1 | 方向四：CI/CD 安全加固 | 2-3 天 | 依赖漏洞/密钥泄露/注入扫描 |
| 🟡 P1 | 方向五：工具版本兼容性检查 | 2-3 天 | 防止破坏性变更悄悄合入 |
| 🟡 P1 | 方向三：完善 Manifest（capabilities + output schema） | 3-4 天 | 能力可发现 + 返回值可校验 |
| 🟢 P2 | 方向六：AI 项目指令手册 | 1-2 天 | 统一所有 Agent 的项目认知 |

---

## 四、已实现能力清单（无需再改）

以下能力在最新代码中**已经完整实现**，旧版分析文档中标记为"缺失"的项现已解决：

| 能力 | 实现文件 | 旧版状态 | 当前状态 |
|------|---------|---------|---------|
| Prompt 注入防护 | `SafeGuardAdvisor` + `AgentSafetyService` | 🔴 缺失 | ✅ 确定性代码拦截 |
| 情绪危机干预 | `GuardrailService` | 未提及 | ✅ 4 级分级干预 |
| 确定性阶段验收 | `PhaseGate` | 未提及 | ✅ 4 种纯代码检查 |
| 每轮执行契约注入 | `PreALGate` | 未提及 | ✅ 确定性注入 |
| 循环守卫 | `LoopGuardService` | 未提及 | ✅ 正则确定性检测 |
| 上下文压缩 Hook | `CompressionHooks` | 未提及 | ✅ before/after 接口 |
| 评测闭环 + CI 门禁 | `EvalGate` + `eval-gate.yml` | 🔴 缺失 | ✅ 绝对阈值 + 基线回归 |
| 能力漂移检测（系统级） | `EvalBaseline` + `maxRegression` | 🔴 缺失 | ✅ 5% 容忍度 |
| pass^k 稳定性判定 | `PassKCalculator` | 未提及 | ✅ 5 次独立运行 |
| 根因分析 | `RootCauseAnalyzer` | 未提及 | ✅ 5 步聚类定责 |
| Agent 版本协商 | `AgentMetadata` + `AgentVersionNegotiator` | 🔴 缺失 | ✅ 完整 semver 协商 |
| 工具生命周期 | `ToolStatus` 五阶段 | 未提及 | ✅ 设计完整（但执行未拦截） |
| 工具注册中心 | `smart-assistant-tool-registry` 微服务 | 未提及 | ✅ 独立服务 + REST API |
| 工具降级策略 | `ToolRegistryClient` 三级降级 | 未提及 | ✅ 缓存→过期缓存→空列表 |
| 分层 Prompt 管理 | `PromptManager` + `PromptBuilder` | 🟡 弱 | ✅ 文件化 + 版本约定 + canary |
| 入口分级配置 | `ReActProfile` + `ReActProfileProperties` | 未提及 | ✅ 按入口 6 维配置 |
| 目标连续性仲裁 | `GoalContinuityArbiter` | 未提及 | ✅ 确定性+LLM 两级裁决 |
| 熔断 + 限流 + 幂等 | `ToolGateway` 9 步执行链 | 未提及 | ✅ 完整（但熔断无半开状态） |

---

## 五、总结

SmartAssistant 在最新代码中已经实现了两篇文章中**约 80% 的最佳实践**。特别是：

- **确定性安全防线**（SafeGuardAdvisor + GuardrailService + PhaseGate + LoopGuardService）完全符合"确定性代码 > 概率性指令"的原则
- **评测闭环**（EvalGate + GoldenSuite + pass^k + 根因分析）已超越两篇文章的要求
- **工具注册中心**（独立微服务 + REST API + 降级策略）已具备生产级架构

剩余 6 个改进方向集中在**工具执行层的最后一公里**（Pre/PostToolUse Hook + ToolStatus 拦截）和**CI/CD 安全加固**，其中方向二（ToolStatus 拦截）只需 0.5 天即可完成，是投入产出比最高的改进。
