# DeepResearch vs SmartAssistant — 深度对比分析

> **分析时间**: 2026-06-16
> **分析目标**: 从架构设计、Agent 编排、RAG 实现、LLM 集成、部署模型、功能成熟度等维度对两个项目进行全面对比

---

## 一、项目概览

| 维度 | DeepResearch | SmartAssistant |
|------|-------------|---------------|
| **定位** | 智能研究助手 — 针对复杂研究任务的深度 Agent，生成研究报告 | 多智能体客服平台 — 面向客服场景的多 Agent 对话系统 |
| **所属组织** | Spring AI Alibaba 官方示例项目 | 自研项目 |
| **技术栈** | Spring Boot 3.4.8 + Spring AI 1.0.0 + Spring AI Alibaba 1.0.0.4 | Spring Boot 3.4.8 + Spring AI 1.1.2 + Spring AI Alibaba 1.1.2 |
| **LLM 引擎** | DashScope API（通义千问云端模型） | Ollama 本地推理（deepseek-r1:7b / qwen2.5:7b / qwen2.5:1.5b） |
| **前端** | Vue 3 + TypeScript | React 18 + TypeScript + TDesign |
| **构建工具** | Maven | Maven Wrapper |
| **许可证** | Apache 2.0 | MIT |
| **代码规模** | 单模块单体应用（~80+ Java 文件） | 9 个微服务模块（~200+ Java 文件） |
| **Agent 数量** | 7 个 Agent + 条件调度 | 4 个独立 Agent 服务 |
| **团队** | 20+ 贡献者 | 自研项目 |

---

## 二、架构设计对比

### 2.1 架构模式

```
DeepResearch:                  SmartAssistant:
┌──────────────────────┐       ┌──────────────────────────┐
│   单体 Spring Boot   │       │     微服务集群            │
│                      │       │                          │
│  ┌────────────────┐  │       │  Gateway ── Router       │
│  │   StateGraph    │  │       │     │        │           │
│  │  (有向图编排)    │  │       │  Consumer  ┌─┴─┐         │
│  │                │  │       │     │     Order Product   │
│  │  Node → Node   │  │       │  General    User          │
│  └────────────────┘  │       └──────────────────────────┘
│    单进程内流转       │         独立进程 + Nacos 通信
└──────────────────────┘
```

| 维度 | DeepResearch | SmartAssistant |
|------|-------------|---------------|
| **架构风格** | **单体 + Graph DAG 编排**。所有 Agent 在同一 JVM 进程内，通过 `StateGraph` 有向无环图进行状态流转 | **微服务 + A2A 协议**。每个 Agent 独立进程，通过 Nacos 服务发现 + HTTP/SSE 通信 |
| **进程模型** | 单进程，Graph 节点间通过 `OverAllState` 共享状态 | 多进程，服务间通过 HTTP A2A 协议调用 |
| **状态管理** | Graph 内置的 `OverAllState`，支持 `ReplaceStrategy` 策略 | 各服务独立状态，通过 Consumer 管理会话上下文 |
| **服务发现** | 无服务发现（单体架构） | Nacos 自动发现（所有 Agent 注册到 Nacos） |
| **通信协议** | 进程内方法调用 | HTTP REST + SSE 流式 + A2A Agent-to-Agent 协议 |

### 2.2 关键差异分析

**DeepResearch** 的 Graph 架构优势：
- **零网络开销** — Agent 间通信同级进程内方法调用
- **确定性流程** — Graph 有向图确保节点执行顺序和条件分支可控
- **状态快照** — 支持中断/恢复/HITL（人在回路）

**SmartAssistant** 的微服务架构优势：
- **独立扩缩容** — 各 Agent 可独立部署和水平扩展
- **故障隔离** — 单个 Agent 崩溃不影响其他服务
- **技术异构** — 各 Agent 可使用不同的技术栈或模型
- **更贴近生产** — 微服务架构更符合企业级部署需求

---

## 三、Agent 编排与协作

### 3.1 Agent 类型对比

| DeepResearch Agent | 职责 | SmartAssistant Agent | 职责 |
|-------------------|------|---------------------|------|
| **Coordinator** | 意图识别（判断是否需要深度研究） | **Router** | 意图分类 + 任务分解 + 结果合并 |
| **Background Investigator** | 背景调查（多源搜索） | — | （由 Router 的 TaskPlanner 替代） |
| **Rewrite & Multi-Query** | 查询改写与多查询扩展 | — | （RAG 中类似机制） |
| **Planner** | 制定研究计划 | **TaskPlannerService** | 任务分解为子任务 |
| **Researcher (并行)** | 并行研究执行 | **Order/Product** | 领域专用 Agent |
| **Coder (并行)** | Python 代码执行/数据分析 | — | 无代码执行能力 |
| **Professional KB Decision** | 判断是否使用专业知识库 | — | （直接路由到对应 Agent） |
| **RAG** | 知识库检索 | **Travel RAG** | 游记/餐厅 RAG |
| **Human Feedback** | 人工反馈节点 | — | 无 HITL 机制 |
| **Reporter** | 研究报告生成 | **General Agent** | 闲聊/天气/工具 |
| **Short User Role Memory** | 用户角色记忆提取 | **Consumer (Memory)** | 记忆/价值评估 |

### 3.2 编排流程对比

**DeepResearch Graph 流程：**

```
START → 用户角色记忆 → Coordinator 
  → (判断深度研究) → Rewrite Multi-Query 
  → Background Investigation 
  → Planner (制定研究计划)
  → Information (信息分发)
    → Human Feedback ↔ Research Team (并行 Researcher + Coder)
    → Professional KB Decision → RAG
  → Reporter (生成报告) → END
```

**SmartAssistant 路由流程：**

```
用户请求 → Gateway (JWT认证+限流) 
  → Router.TaskPlanner (LLM意图分解)
    → Consumer (会话管理+价值评估)
      → [Order Agent / Product Agent / General Agent]
        → A2A 协议调用
        → 结果合并 (ResultMerger)
        → 反思器 (五维评分)
          → 通过 → 写入语义缓存 → 返回
          → 不通过 → fallback 重试
```

### 3.3 关键差异

| 维度 | DeepResearch | SmartAssistant |
|------|-------------|---------------|
| **编排引擎** | `spring-ai-alibaba-graph-core` (`StateGraph`) | 自研 `TaskPlannerService` + LLM 分解 |
| **任务分解** | Planner Node + LLM 制定研究计划 | TaskPlanner Service + LLM 分解为子任务 |
| **并行执行** | 内置并行 Researcher/Coder 节点 (可配置数量) | `executeCollaborative()` 并行调用多个 Agent |
| **人在回路(HITL)** | ✅ 有 `HumanFeedbackNode` 支持用户反馈 | ❌ 无显式 HITL |
| **反思机制** | `ReflectionProcessor` (LLM 评估输出质量) | `ReflectionService` (纯规则五维评分，零 LLM 调用) |
| **结果合并** | Reporter Node 综合生成报告 | ResultMerger.merge() LLM 整合 |
| **兜底策略** | 沿 Graph 条件边走 END | `inlineFallback()` 内联 ChatClient |
| **共享上下文** | `OverAllState` 全局状态 | `sharedContext Map` + 顺序执行注入 |

---

## 四、LLM 集成

| 维度 | DeepResearch | SmartAssistant |
|------|-------------|---------------|
| **LLM 提供商** | DashScope API（阿里云通义千问） | Ollama 本地推理（无云端依赖） |
| **模型** | 通义千问系列（云端） | deepseek-r1:7b / qwen2.5:7b / qwen2.5:1.5b |
| **API Key 要求** | ✅ 必须 `AI_DASHSCOPE_API_KEY` | ❌ 无需 API Key |
| **数据安全性** | 数据经过云端 API | 🔒 纯本地推理，数据不出内网 |
| **多模型支持** | 通过 Spring AI Model 切换 | 通过 Ollama 模型切换 |
| **Embedding** | DashScope Embedding API | BGE-small-zh ONNX (512d) / BGE-large-zh ONNX (1024d) |
| **流式输出** | SSE 流式（Graph 内置支持） | SSE + WebSocket 流式 |
| **并发控制** | 无显式限流 | Semaphore 限流（默认 5 并发）+ 请求排队 |

### 关键差异

1. **DeepResearch 依赖云端 API**：所有推理通过 DashScope API，虽然质量更高但需要网络和 API Key
2. **SmartAssistant 纯本地推理**：基于 Ollama，零外部依赖，适合内网/合规场景
3. **Embedding 方式不同**：DeepResearch 用 DashScope API，SmartAssistant 用本地 BGE ONNX（30-50ms/次）
4. **深度研究场景**：DeepResearch 的云端模型更擅长复杂推理任务；SmartAssistant 的 7B 本地模型在客服场景足够

---

## 五、RAG 实现对比

| 维度 | DeepResearch | SmartAssistant |
|------|-------------|---------------|
| **向量引擎** | Elasticsearch | PostgreSQL pgvector |
| **检索模式** | Hybrid RAG（向量 + 关键词）+ RRF 融合 | 多路召回（向量 + 全文检索）+ RRF 融合 |
| **查询处理** | Multi-Query Expansion + HyDe + 翻译 | Multi-Query 改写（LLM） |
| **Embedding 模型** | DashScope Embedding API | BGE-large-zh ONNX (1024d) + BGE-small-zh ONNX (512d) |
| **重排序** | RRF Fusion + 综合评分 | BGE 语义重排序（RRF×0.3 + BGE余弦×0.7） |
| **知识库** | Elasticsearch 索引（支持上传文档） | PostgreSQL 表（游记/餐厅数据） |
| **Filter** | BoolQuery（source_type + user_id + session_id） | FilterExpressionBuilder（location + category） |
| **去重** | ✅ 按 ID/内容去重 | ❌ 无显式去重 |
| **Lost in the Middle** | ❌ 无 | ✅ 首尾交替排列优化 |

### 关键差异

1. **向量存储不同**：DeepResearch 用 ES（适合搜索场景），SmartAssistant 用 pgvector（与业务数据同库）
2. **纯本地 vs API**：SmartAssistant 的 BGE ONNX 纯本地推理（30-50ms），DeepResearch 依赖 API
3. **重排序策略不同**：SmartAssistant 有 BGE 重排序，DeepResearch 使用 RRF 综合评分
4. **查询扩展**：DeepResearch 的 HyDe（假设性文档嵌入）是独特设计，SmartAssistant 无此功能
5. **Knowledge Base 管理**：DeepResearch 有完整的 KB 管理界面（CRUD + 上传），SmartAssistant 为静态 SQL 数据

---

## 六、缓存与记忆

| 维度 | DeepResearch | SmartAssistant |
|------|-------------|---------------|
| **对话记忆** | `MessageWindowChatMemory` + Redis 持久化 | 文件存储 `data/users/{userId}/memories/` |
| **短期记忆** | ✅ `ShortTermMemoryRepository` | ❌ 无显式短期记忆 |
| **用户角色记忆** | ✅ Self-evolution User Role Memory（LLM 提取+进化） | ✅ 价值评估 + 叙事摘要 |
| **语义缓存** | ❌ 无 | ✅ 三层语义缓存（精确/关键词/BGE 向量） |
| **路由缓存** | ❌ 无（单体架构不需要） | ✅ 路由决策缓存（Redis，TTL 24h） |
| **回复缓存** | ❌ 无 | ✅ 动态 TTL 回复缓存 + 前缀个性化 |
| **记忆格式** | LLM 提取的结构化角色信息 | Markdown 叙事+原文混合存储 |
| **记忆快照** | Redis 序列化/反序列化 | 增量文件追加 |

### 关键差异

SmartAssistant 的**缓存体系远更完善**，因为微服务架构下 Router 到 Agent 的调用开销大（5-18s），缓存可以大幅提升响应速度（1-5ms）。DeepResearch 单体架构无此需求。

---

## 七、可观测性与监控

| 维度 | DeepResearch | SmartAssistant |
|------|-------------|---------------|
| **APM 指标** | Micrometer + OpenTelemetry | Micrometer + Prometheus |
| **链路追踪** | ✅ OpenTelemetry → Langfuse（可选） | ✅ Jaeger + Zipkin |
| **可视化** | Langfuse 云端（可选） | Grafana + 8 个自定义仪表盘 |
| **日志聚合** | ❌ 无 | ✅ Loki + Promtail |
| **告警规则** | ❌ 无 | ✅ Prometheus AlertManager + 自定义规则 |
| **健康检查** | Spring Boot Actuator | Spring Boot Actuator |
| **SQL 监控** | ❌ 无 | ✅ pg_stat_statements + 自定义 SQL 告警 |

---

## 八、安全与可靠性

| 维度 | DeepResearch | SmartAssistant |
|------|-------------|---------------|
| **AI 防护** | Secure Sandbox（Docker 内执行 Python 代码） | AST 级 SQL 注入防护（jsqlparser 白名单） |
| **认证** | ❌ 无内置认证 | ✅ JWT 认证 + 角色管理 |
| **限流** | ❌ 无 | ✅ Redis 限流 + Semaphore 并发控制 |
| **排队机制** | ❌ 无 | ✅ SSE 排队通知（位置+预计等待时间） |
| **优雅关闭** | ❌ 无 | ✅ 所有服务配置 graceful shutdown |
| **灰度开关** | ❌ 无 | ✅ reflection.enabled / cache.enabled |
| **密码管理** | 环境变量注入 | 环境变量注入（强制要求，无默认值） |

---

## 九、功能全面性对比

| 功能 | DeepResearch | SmartAssistant |
|------|:-----------:|:-------------:|
| 多 Agent 协作 | ✅ Graph DAG 编排 | ✅ A2A 协议+TaskPlanner |
| 深度研究 | ✅ 核心功能 | ❌ |
| 研究报告生成 | ✅ HTML/PDF/Markdown | ❌ |
| Python 代码执行 | ✅ Docker Sandbox | ❌ |
| 联网搜索 | ✅ Tavily/Jina/阿里云 AI Search | ✅ searchWeb() |
| 在线知识库 | ✅ ES 知识库管理 | ❌（仅有预置数据） |
| MCP 服务 | ✅ 支持（高德地图等） | ✅ 支持（SQL MCP） |
| 多模态 | ❌ | ✅ 图片解析 + 文生图 |
| 订单查询 | ❌ | ✅ |
| 商品咨询 | ❌ | ✅ |
| 闲聊问答 | ❌（研究导向） | ✅ |
| 天气查询 | ❌ | ✅ |
| 语义缓存 | ❌ | ✅ 三层缓存 |
| 请求排队 | ❌ | ✅ |
| 全链路可观测 | ✅（Langfuse 可选） | ✅（Grafana+Jaeger+Loki） |
| 人在回路 | ✅ | ❌ |
| 反思机制 | ✅ LLM 反思 | ✅ 纯规则五维评分 |
| 认知记忆 | ✅ Self-evolution | ✅ 叙事摘要 |
| 认证授权 | ❌ | ✅ JWT + 角色管理 |
| SQL 防护 | ❌ | ✅ AST 级白名单 |
| 前端界面 | ✅ Vue 3 管理端 | ✅ React 对话界面 |

---

## 十、综合对比总结

### DeepResearch 的优势

1. **深度研究能力** — 端到端的研究报告生成，从问题分解到多源搜索、并行研究执行再到报告撰写
2. **Graph 编排** — `StateGraph` 有向无环图使流程确定性强，支持中断/恢复/HITL
3. **代码执行** — Docker Sandbox 安全执行 Python 代码，支持数据分析
4. **知识库管理** — 完整的知识库 CRUD + 文档上传管理界面
5. **MCP 扩展** — 支持 MCP 协议的服务分配，可扩展性强
6. **查询预处理** — HyDe（假设性文档嵌入）、查询翻译、多查询扩展等丰富的 RAG 预处理管道

### SmartAssistant 的优势

1. **生产就绪** — 完整的认证、限流、排队、监控、告警体系，更贴近生产环境
2. **纯本地推理** — 基于 Ollama 的本地推理，数据不出内网，零 API 成本
3. **三层语义缓存** — 精确/关键词/向量匹配，缓存命中时 1-5ms 响应
4. **A2A 协议** — 基于 Spring AI Alibaba A2A 的标准 Agent 通信协议，标准化程度高
5. **微服务架构** — 独立扩缩容、故障隔离、技术异构
6. **安全管理** — JWT 认证、AST 级 SQL 防护、角色权限管理
7. **多模态** — 图片解析（analyzeImage）和文生图（generateImage）
8. **全链路可观测** — Prometheus + Grafana + Jaeger + Loki 完整监控栈
9. **反思器** — 纯规则五维评分，零 LLM 调用，无额外延迟
10. **客服场景深度** — 订单查询、商品咨询、闲聊天气等客服场景功能完善

### 架构决策差异的核心原因

```
DeepResearch:                          SmartAssistant:
研究 → 追求深度推理 + 全面检索          客服 → 追求低延迟 + 高可用 + 安全合规
  │                                        │
  ├─ 云端 LLM → 更强推理能力              ├─ 本地 LLM → 数据安全
  ├─ 单体 Graph → 状态管理简单             ├─ 微服务 A2A → 独立部署
  ├─ ES RAG → 适合搜索场景                ├─ pgvector → 与业务数据同库
  ├─ Sandbox Python → 数据分析             ├─ 语义缓存 → 降低延迟
  └─ Langfuse → 可观测                    └─ Grafana+Jaeger → 全栈可观测
```

### 可以互相借鉴的点

**SmartAssistant 可以向 DeepResearch 学习：**
1. 引入 Graph 编排引擎替代自研 TaskPlanner，提高任务分解的确定性
2. 增加代码执行能力（Docker Sandbox）以支持数据分析场景
3. 引入 HyDe（假设性文档嵌入）提升 RAG 精度
4. 增加人在回路（HITL）机制，提升复杂问题的交互质量
5. 完善知识库管理，支持动态上传文档

**DeepResearch 可以向 SmartAssistant 学习：**
1. 增加语义缓存，减少重复 API 调用
2. 引入 JWT 认证保障 API 安全
3. 完善可观测性栈（Prometheus + Grafana + Loki）
4. 增加请求排队机制提升用户体验
5. 引入微服务拆分方案，支持独立部署
6. 增加多模态支持（图片理解/生成）
