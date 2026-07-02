# Multi-Agent 架构设计——文章观点 vs 项目现状

> 原文：《AI Agent探幽 | 面试挂了10家的原因竟然是不知道multi-agents怎么设计？》（逻码帝国，2026-06-15）
> 对照项目：SmartAssistant
> 分析日期：2026-07-02

---

## 文章核心内容

文章介绍了一个 CLI 工具 (`Paulo-CLI`) 的 Multi-Agent 设计实践，覆盖以下主题：

1. **三种 Agent 角色**：Master Agent（主循环）、Sub Agent（函数式调用）、Agents Team（持久化队友）
2. **通信机制**：文件收件箱（JSONL 追加写）、共享任务板（文件系统）
3. **A2A 协议**：JSON Card 点对点通信
4. **HITL 五层纵深防护**：路径沙箱、命令黑名单、审批门禁、Plan 只读白名单、子 Agent 权限分级
5. **Agent 权限分级**：readonly / editor / full 三级预设

---

## 逐条对照

### 1. Multi-Agent 架构

| 文章观点 | 项目现状 | 覆盖度 |
|---------|---------|:------:|
| Master Agent 主循环 | Router 模块负责意图路由和任务分发 | ✅ |
| Sub Agent 函数式调用 | `GraphExecutionService.executeNode()` 调用 Agent 并收集结果 | ✅ |
| Agents Team 持久化队友 | `HotAgentPool` 常驻内存 + `AgentHealthChecker` 30s 心跳 | ✅ |

**说明**：项目架构是 Master-Subagent 的微服务版——Router 是 Master，Order/Product/General 是 Sub Agent。HotAgentPool 实现持久化 Agent 常驻。

---

### 2. 通信机制

| 文章观点 | 项目现状 | 覆盖度 |
|---------|---------|:------:|
| 文件收件箱（JSONL 追加写） | 项目使用 HTTP IPC + Redis，非文件系统 | 🟦 不适用 |
| 共享任务板（文件系统） | `TaskPlannerService` DAG 拓扑 → `GraphExecutionService` 并行执行 | ✅ |
| A2A 协议（JSON Card） | `HandoffCommand` record + `AgentMetadata.supportedProtocols(a2a-v1/v2)` | ✅ |

**说明**：文章面向 CLI 工具，文件系统通信适合单机场景。项目面向微服务架构，HTTP + Redis 通信是更合理的选择。

---

### 3. HITL 五层纵深防护

| 层次 | 文章方案 | 项目现状 | 覆盖度 |
|:----|:---------|:---------|:------:|
| L1 | 路径沙箱（safe_path resolve + is_relative_to） | `ScriptSandbox` 脚本沙箱 + 路径黑名单 | ✅ |
| L2 | 命令黑名单（rm/sudo/shutdown） | `ScriptSandbox` 危险关键字黑名单 | ✅ |
| L3 | HITL 审批门禁（y/a/n） | `ApprovalService` 二阶段确认（`PENDING→CONFIRMED→CONSUMED`） | ✅ |
| L4 | Plan 模式只读白名单（6 工具） | `SubTask.successCriteria` + `ReflectionService.checkCriteria` 验收 | ✅ |
| L5 | 子 Agent 权限分级 | `ToolRiskLevel` + `ToolDefinition.scopes[]` + `ToolGateway` 鉴权 | ✅ |

**说明**：项目在 HITL 方面的实现比文章更严谨——`ApprovalService` 使用充血状态机 + `synchronized` + DB `WHERE status=` 三重防护，并发安全基准测试覆盖。

---

### 4. Agent 权限分级（唯一潜在缺口）

| 文章观点 | 项目现状 | 覆盖度 |
|---------|---------|:------:|
| 3 级预设：readonly / editor / full | `ToolRiskLevel` 细粒度四层 + `ToolDefinition.scopes[] ` 请求级鉴权 | ⚠️ 粒度不同 |

**分析**：文章提出的是 Agent 级别的权限预设（spawn 时指定），项目实现的是工具级别的细粒度控制。差异在于：
- 文章：粗粒度预设 → 简单，适用 CLI
- 项目：细粒度控制 → 灵活，适用微服务

在项目的 Agent 固定架构下（非动态 spawn），工具级控制更合适。

---

## 覆盖度总览

| 覆盖度 | 数量 | 条目 |
|:------|:----:|:-----|
| ✅ **已覆盖/超越** | 8 | Master-Subagent、Agents Team、A2A 协议、路径沙箱、命令黑名单、审批门禁、验收机制、权限控制 |
| 🟦 **不适用** | 2 | 文件收件箱（CLI 场景）、共享任务板文件系统（CLI 场景） |
| ⚠️ **部分覆盖** | 0 | — |
| ❌ **缺口** | 0 | — |

---

## 结论

**技术密度评估：低**

与前两篇文章（Agent 10 深水题、RAG 动态更新）相比，这篇文章的内容更偏向入门级概念介绍，面向学习型读者而非生产级工程师。文章中的关键设计模式（Master-Subagent、A2A、HITL、权限管理）SmartAssistant 均已实现，且在工程可靠性上做得更深入：

| 对比维度 | 文章（Paulo-CLI） | 项目（SmartAssistant） |
|:---------|:-----------------|:----------------------|
| 场景 | CLI 工具 | 微服务集群 |
| HITL | 终端弹窗确认 | 充血状态机 + DB 原子操作 |
| 通信 | 文件追加写 | HTTP + Redis |
| 权限 | 3 级预设 | 4 级风险 + Scope 鉴权 |
| 执行引擎 | while True 循环 | DAG 拓扑并行 + Handoff + 重规划 |

**改进建议：无需改动。**

项目中已有的实现覆盖了这篇文章提出的所有核心设计模式，且工程实现质量更高。
