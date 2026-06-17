# Spring AI Alibaba 生态三大项目深度对比

> **分析时间**: 2026-06-16
> **分析项目**: DeepResearch / SmartAssistant / AssistantAgent
> **分析目标**: 从定位、架构、Agent 编排、RAG、LLM 集成、缓存记忆、安全运维等维度进行全面对比

---

## 一、项目概览

### 一句话定义

| 项目 | 定位 | 一句概括 |
|------|------|---------|
| **DeepResearch** | 🎯 **研究引擎** | 基于 Graph DAG 编排的**深度研究报告生成系统**，面向复杂研究任务 |
| **SmartAssistant** | 🏪 **客服平台** | 基于微服务 + A2A 协议的**多 Agent 对话客服平台**，面向客服场景 |
| **AssistantAgent** | 🧩 **企业级智能助手框架** | 基于 Code-as-Action 范式的**可学习、可扩展的智能助手框架**，面向企业能力复用 |

### 基础信息

| 维度 | DeepResearch | SmartAssistant | AssistantAgent |
|------|-------------|---------------|----------------|
| **所属组织** | Spring AI Alibaba 官方 | 自研项目 | Spring AI Alibaba 官方 |
| **技术栈** | Spring Boot 3.4.8 + Spring AI 1.0.0 + Alibaba 1.0.0.4 | Spring Boot 3.4.8 + Spring AI 1.1.2 + Alibaba 1.1.2 | Spring Boot 3.4.8 + Spring AI 1.1.0 + Alibaba 1.1.2.2 |
| **LLM 引擎** | DashScope API（通义千问云端） | Ollama 本地推理（deepseek-r1:7b / qwen2.5） | DashScope API（qwen-max 云端） |
| **代码规模** | 单体 ~80+ Java 文件 | 9 微服务 ~200+ Java 文件 | 8 模块 ~150+ Java 文件 |
| **前端** | Vue 3 + TypeScript | React 18 + TDesign | 无（管理 API 仅 REST） |
| **许可证** | Apache 2.0 | MIT | Apache 2.0 |
| **发展阶段** | 🟢 成熟示例项目 | 🟢 生产就绪 | 🟡 第一阶段（半集成框架） |
| **版本号** | 0.1.0-SNAPSHOT | 1.0.0 | 0.2.7 |

---

## 二、架构设计对比

### 2.1 架构模式

```
DeepResearch:                     SmartAssistant:                   AssistantAgent:
┌──────────────────────┐          ┌──────────────────────┐         ┌──────────────────────────┐
│   单体 Spring Boot   │          │     微服务集群        │         │   模块化框架（SPI）       │
│                      │          │                      │         │                          │
│  ┌────────────────┐  │          │  Gateway ── Router   │         │  ┌────────────────────┐  │
│  │   StateGraph    │  │          │     │        │       │         │  │  评估引擎(评估图)   │  │
│  │  (有向图编排)    │  │          │  Consumer  ┌─┴─┐     │         │  └────────┬───────────┘  │
│  │                │  │          │     │     Order Product│         │           ▼              │
│  │  Node → Node   │  │          │  General    User      │         │  ┌────────────────────┐  │
│  └────────────────┘  │          └──────────────────────┘          │  │  Prompt Builder    │  │
│    单进程内流转       │            独立进程 + Nacos 通信            │  │  (动态组装)         │  │
└──────────────────────┘                                            │  └────────┬───────────┘  │
                                                                    │           ▼              │
                                                                    │  ┌────────────────────┐  │
                                                                    │  │  核心执行引擎       │  │
                                                                    │  │  GraalVM 沙箱      │  │
                                                                    │  │  (Code-as-Action)  │  │
                                                                    │  └────────────────────┘  │
                                                                    │           │              │
                                                                    │  ┌────────┼────┬────────┐ │
                                                                    │  │ 经验    │学习 │ 搜索   │ │
                                                                    │  │ 模块    │模块 │ 模块   │ │
                                                                    │  └────────┴────┴────────┘ │
                                                                    │                          │
                                                                    │  SPI 扩展:MCP/HTTP/渠道   │
                                                                    └──────────────────────────┘
```

### 2.2 架构要素对比

| 维度 | DeepResearch | SmartAssistant | AssistantAgent |
|------|-------------|---------------|----------------|
| **架构风格** | **单体 Graph DAG** — 单 JVM 进程，有向无环图编排 | **微服务 A2A** — 多进程独立部署，HTTP 协议通信 | **模块化框架 + SPI** — 单进程多模块，SPI 插件式扩展 |
| **进程模型** | 单进程，Graph 节点共享 `OverAllState` | 多进程，HTTP A2A 协议调用 | 单进程，模块间接口调用 |
| **服务发现** | 无（单体无需发现） | Nacos 自动发现 | 无（依赖 Spring Bean） |
| **扩展机制** | 代码内节点新增 | Nacos 服务注册即可发现新 Agent | **SPI 接口** — SearchProvider / ReplyChannel / CodeactTool |
| **状态管理** | `OverAllState` + `ReplaceStrategy` | Consumer 会话上下文 + Redis | Graph `OverAllState`（评估图） |
| **构建交付** | 单体 JAR | 多服务独立 JAR + Docker | 多模块 JAR + Spring Boot Starter |

---

## 三、核心范式对比

这是三者**最根本的区别**，决定了它们的适用场景：

| 维度 | DeepResearch | SmartAssistant | AssistantAgent |
|------|-------------|---------------|----------------|
| **Agent 交互范式** | **Graph 流式编排** — LLM 在 Graph 节点中被调用，流程由 Graph 定义 | **A2A 协议路由** — LLM 做意图分解→路由到独立 Agent→合并结果 | **Code-as-Action** — LLM 生成代码 → GraalVM 沙箱执行 → 代码中组合工具 |
| **"智能"的体现** | 研究流程的编排和规划 | 意图分类和任务路由 | **代码生成与执行** |
| **核心依赖** | `spring-ai-alibaba-graph-core` | `spring-ai-alibaba-a2a` | `graalvm.polyglot` (JS/Python 执行器) |
| **框架定位** | **应用示例** — 展示 Graph 编排能力 | **完整系统** — 生产级多 Agent 客服 | **开发框架** — 供开发者二次定制的智能助手框架 |
| **开发者角色** | 直接使用 | 直接部署运维 | **需要二次开发** — 实现 SPI 接口接入业务 |

### 3.1 Code-as-Action vs Tool Calling

```java
// SmartAssistant 的传统 Tool Calling
@Tool("查询订单")
public String queryOrder(String orderId) {
    return orderService.query(orderId);
}
// LLM 只能调用预设的、离散的工具函数

// AssistantAgent 的 Code-as-Action
// LLM 生成代码：
String code = """
    result = query_order("ORD-001")
    if result.status == "delivered":
        send_notification(result.user, "您的订单已送达")
    else:
        tracking = track_delivery("ORD-001")
        result["tracking"] = tracking
    return result
""";
// GraalVM 执行 → 代码内动态组合多个工具
```

---

## 四、Agent 编排与协作

### 4.1 Agent/节点类型对比

| DeepResearch 节点 | SmartAssistant Agent | AssistantAgent 模块 |
|-------------------|---------------------|-------------------|
| **Coordinator** (意图识别) | **Router.TaskPlanner** (任务分解) | **Evaluation Graph** (多维评估图) |
| **Background Investigator** (背景调查) | — | **Search Module** (统一搜索 SPI) |
| **Rewrite & Multi-Query** (查询改写) | **RAG Multi-Query** (LLM 改写) | — |
| **Planner** (研究计划) | **TaskPlannerService** (子任务分解) | **Prompt Builder** (动态 Prompt 组装) |
| **Researcher** (并行研究) | **Order Agent / Product Agent** | **Code Generator + Executor** (代码生成执行) |
| **Coder** (Python 执行) | — | **GraalCodeExecutor** (JS/Python 沙箱) |
| **Professional KB** (专业库决策) | — | **SearchProvider** 知识库 SPI |
| **RAG Node** (知识检索) | **Travel RAG** (pgvector 检索) | **Search Knowledge** 模块 |
| **Human Feedback** (人在回路) | — | 无 |
| **Reporter** (报告生成) | **ResultMerger.merge()** (结果合并) | **Reply Module** (多渠道回复) |
| **Short User Role Memory** | **Consumer** (记忆/价值评估) | **Experience + Learning** 模块 |
| — | **Gateway** (JWT + 限流) | **Trigger Module** (定时/延迟/事件) |
| — | **General Agent** (闲聊/工具) | **Management API** (经验 CRUD) |

### 4.2 编排流程对比

**DeepResearch — Graph 有向图：**

```
START → 角色记忆 → Coordinator → (深度研究?) 
  → Rewrite-Query → BackgroundInvestigation 
  → Planner → Information → [HumanFeedback ↔ ResearchTeam(并行R+C)]
  → ProfessionalKB → RAG → Reporter → END
```

**SmartAssistant — A2A 协议路由：**

```
Gateway(JWT+限流) → Router.TaskPlanner(LLM分解)
  → Consumer(会话管理)
    → A2A调用[Order/Product/General]
    → ResultMerger → 反思器(五维评分)
    → 写入语义缓存 → 返回
```

**AssistantAgent — Code-as-Action：**

```
用户请求 → Evaluation Graph(多维评估)
  → Prompt Builder(动态组装Prompt)
    → LLM 生成代码
    → GraalVM 沙箱执行代码(组合多个工具)
      → 经验提取(Learning Module)
      → 经验存储(Experience Module)
    → 多渠道回复(Reply Module)
```

### 4.3 关键差异

| 维度 | DeepResearch | SmartAssistant | AssistantAgent |
|------|-------------|---------------|----------------|
| **编排引擎** | `StateGraph` 有向无环图 | 自研 `TaskPlanner` + A2A 协议 | `Evaluation Graph` + `CodeExecutor` |
| **核心 AI 能力** | 流程规划与信息整合 | 意图分类与路由决策 | **代码生成与工具组合** |
| **并行执行** | ✅ 并行 Researcher/Coder 节点 | ✅ `executeCollaborative()` 多 Agent | ✅ 单 Agent 代码内组合多工具 |
| **人在回路** | ✅ HumanFeedbackNode | ❌ | ❌ |
| **反思/评估** | ✅ LLM 反思 | ✅ 纯规则五维评分 | ✅ **评估图** (LLM + 规则 + 多模态) |
| **经验复用** | ❌ | ❌ | ✅ **统一经验体系** (COMMON/REACT/TOOL) |
| **学习能力** | ❌ | ❌ | ✅ **自动学习提取经验** |
| **结果合并** | Reporter Node | ResultMerger.merge() | 代码内直接组合 |

---

## 五、LLM 集成

| 维度 | DeepResearch | SmartAssistant | AssistantAgent |
|------|-------------|---------------|----------------|
| **LLM 提供商** | DashScope API（云端） | Ollama 本地推理 | DashScope API（云端） |
| **默认模型** | 通义千问（云端系列） | deepseek-r1:7b / qwen2.5:7b | qwen-max |
| **API Key 要求** | ✅ 必须 | ❌ 无需 | ✅ 必须 |
| **数据安全性** | 云端 API 传输 | 🔒 **纯本地，数据不出内网** | 云端 API 传输 |
| **代码执行方式** | Docker Sandbox (Python) | 无代码执行 | **GraalVM Polyglot 沙箱** (JS/Python) |
| **Embedding** | DashScope Embedding API | BGE ONNX (纯本地 30-50ms) | 无独立 Embedding 模块 |
| **流式输出** | ✅ SSE (Graph 内置) | ✅ SSE + WebSocket | ✅ SSE |
| **并发控制** | ❌ 无 | ✅ Semaphore(5) + 排队 | ❌ 无 |

### 关键差异

1. **DeepResearch + AssistantAgent 依赖云端**，SmartAssistant 纯本地
2. **执行范式不同**：SmartAssistant 调用预定义工具 → AssistantAgent 生成代码并执行 → DeepResearch 在 Graph 节点内调用 LLM
3. **GraalVM 沙箱**是 AssistantAgent 独有的核心技术，支持在安全环境中执行 JS/Python
4. SmartAssistant 的 **Ollama 本地推理**在数据安全上有根本优势

---

## 六、RAG 与知识检索

| 维度 | DeepResearch | SmartAssistant | AssistantAgent |
|------|-------------|---------------|----------------|
| **向量引擎** | Elasticsearch | PostgreSQL pgvector | **SPI 可插拔**（无默认实现） |
| **检索模式** | Hybrid RAG (向量+关键词) + RRF | 多路召回 + RRF 融合 | 通过 `SearchProvider` SPI 扩展 |
| **查询处理** | Multi-Query + HyDe + 翻译 | Multi-Query LLM 改写 | 无内置（可自定义） |
| **Embedding** | DashScope Embedding API | BGE-large ONNX (1024d) | 无内置（由 SearchProvider 实现） |
| **重排序** | RRF 综合评分 | BGE 语义重排序 (RRF×0.3 + BGE×0.7) | 无内置 |
| **知识库管理** | ✅ ES 知识库 CRUD + 上传 | ❌ 仅有预置 SQL 数据 | ✅ **SPI 接口**，开发者对接任意知识源 |
| **Lost in the Middle** | ❌ | ✅ 首尾交替排列 | ❌ |

### 关键差异

- **SmartAssistant 是唯一纯本地的 RAG**（BGE ONNX），零 API 依赖
- **AssistantAgent 采用 SPI 扩展**，不内置任何搜索实现，由开发者决定接入什么知识源
- **DeepResearch 有完整的知识库管理界面**，可直接上传文档
- SmartAssistant 的 **BGE 语义重排序 + Lost in the Middle** 是三者中最完善的 RAG 后处理

---

## 七、缓存、记忆与经验

| 维度 | DeepResearch | SmartAssistant | AssistantAgent |
|------|-------------|---------------|----------------|
| **对话记忆** | MessageWindowChatMemory + Redis | 文件存储 `data/users/{userId}/memories/` | ❌ 无内置（可自定义） |
| **短期记忆** | ✅ ShortTermMemoryRepository | ❌ | ❌ |
| **用户角色记忆** | ✅ Self-evolution（LLM 提取+进化） | ✅ 价值评估 + 叙事摘要 | ❌ |
| **语义缓存** | ❌ | ✅ **三层缓存** (精确/关键词/BGE 向量) | ❌ |
| **路由缓存** | ❌（单体不需要） | ✅ Redis TTL 24h | ❌ |
| **回复缓存** | ❌ | ✅ 动态 TTL + 前缀个性化 | ❌ |
| **经验体系** | ❌ | ❌ | ✅ **统一经验模型** (COMMON/REACT/TOOL) |
| **经验复用** | ❌ | ❌ | ✅ 快速意图 + 渐进式披露 |
| **自动学习** | ❌ | ❌ | ✅ 从执行历史自动提取经验 |
| **经验管理 API** | ❌ | ❌ | ✅ CRUD + 统计 + SKILL 导入导出 |

### 认知演进能力对比

```
SmartAssistant:                              AssistantAgent:
一次对话 → 记忆文件                          一次执行 → 经验提取
  ↓                                             ↓
叙事摘要（用户记忆）                          经验存储（COMMON/REACT/TOOL）
  ↓                                             ↓
下次参考记忆                                   下次相似场景→快速响应
                                              跳过 LLM 推理

DeepResearch:
一次研究 → 无持久化经验
```

> **AssistantAgent 的经验体系是三者中唯一的"越用越聪明"机制**

---

## 八、可观测性与运维

| 维度 | DeepResearch | SmartAssistant | AssistantAgent |
|------|-------------|---------------|----------------|
| **APM 指标** | Micrometer + OpenTelemetry | Micrometer + Prometheus | ✅ OpenTelemetry (全链路可观测) |
| **链路追踪** | ✅ Langfuse (可选) | ✅ Jaeger + Zipkin | ✅ OpenTelemetry |
| **可视化** | Langfuse 云端 | ✅ **Grafana + 8 仪表盘** | ❌ 无 |
| **日志聚合** | ❌ | ✅ Loki + Promtail | ❌ |
| **告警** | ❌ | ✅ Prometheus AlertManager | ❌ |
| **管理后台** | ❌ | ❌ | ✅ **经验管理 REST API** |
| **健康检查** | ✅ Actuator | ✅ Actuator | ✅ Actuator |
| **SQL 监控** | ❌ | ✅ pg_stat_statements | ❌ |

### 安全与可靠性

| 维度 | DeepResearch | SmartAssistant | AssistantAgent |
|------|-------------|---------------|----------------|
| **代码沙箱** | ✅ Docker Sandbox (Python) | ❌ | ✅ **GraalVM Polyglot 沙箱** |
| **认证授权** | ❌ | ✅ JWT + 角色管理 | ❌ |
| **SQL 防护** | ❌ | ✅ AST 级 jsqlparser 白名单 | ❌ |
| **限流** | ❌ | ✅ Redis 限流 + Semaphore | ❌ |
| **排队机制** | ❌ | ✅ SSE 排队通知 | ❌ |
| **触发器** | ❌ | ❌ | ✅ **定时/延迟/事件触发** |
| **多渠道回复** | ❌ | ❌ | ✅ SPI 扩展（钉钉/飞书/企微等） |

---

## 九、功能全景对比

| 功能 | DeepResearch | SmartAssistant | AssistantAgent |
|------|:-----------:|:-------------:|:--------------:|
| **多 Agent 协作** | ✅ Graph DAG | ✅ A2A + TaskPlanner | ❌ (单 Agent) |
| **深度研究/报告** | ✅ 核心功能 | ❌ | ❌ |
| **代码执行** | ✅ Docker Sandbox | ❌ | ✅ **GraalVM Polyglot** |
| **联网搜索** | ✅ Tavily/Jina/阿里云 | ✅ searchWeb() | ✅ SearchProvider SPI |
| **在线知识库** | ✅ ES 知识库管理 | ❌ 预置数据 | ✅ SPI 可扩展 |
| **MCP 服务** | ✅ 高德地图等 | ✅ SQL MCP | ✅ **MCP + HTTP API + 自定义** |
| **多模态** | ❌ | ✅ 图片解析+文生图 | ❌ |
| **订单/商品查询** | ❌ | ✅ 核心能力 | ❌ |
| **闲聊问答** | ❌ | ✅ | ❌（问答需接入知识库） |
| **天气查询** | ❌ | ✅ | ❌ |
| **语义缓存** | ❌ | ✅ **三层缓存** | ❌ |
| **请求排队** | ❌ | ✅ | ❌ |
| **人在回路** | ✅ | ❌ | ❌ |
| **反思/评估** | ✅ LLM 反思 | ✅ 规则五维评分 | ✅ **评估图** (LLM+规则+多模态) |
| **认知记忆** | ✅ Self-evolution | ✅ 叙事摘要 | ✅ **经验+学习** (独有) |
| **认证授权** | ❌ | ✅ JWT+角色 | ❌ |
| **SQL/AI 防护** | ❌ Sandbox | ✅ AST 白名单 | ✅ **GraalVM 沙箱** |
| **管理后台** | ❌ | ❌ | ✅ **经验管理 API** |
| **触发器** | ❌ | ❌ | ✅ 定时/延迟/事件 |
| **多渠道回复** | ❌ | ❌ | ✅ SPI (钉钉/飞书等) |
| **Spring Boot Starter** | ❌ | ❌ | ✅ **可集成到现有项目** |

---

## 十、三项目对比总结

### 各自的独有优势

```
DeepResearch:
├─ 📋 端到端研究报告生成（HTML/PDF/Markdown）
├─ 🎯 Graph DAG 确定性流程编排
├─ 📚 ES 知识库管理界面（上传/检索）
├─ 🔄 HyDe 假设性文档嵌入
└─ 👤 人在回路（HITL）机制

SmartAssistant:
├─ 🔒 纯本地 Ollama 推理（数据不出内网）
├─ ⚡ 三层语义缓存（1-5ms 响应）
├─ 🏛️ 微服务 A2A 架构（独立扩缩容）
├─ 🛡️ JWT + AST SQL 防护 + 限流排队
├─ 📊 全栈可观测（Grafana+Jaeger+Loki）
└─ 🖼️ 多模态（图片解析+文生图）

AssistantAgent:
├─ 🧩 框架定位（SPI 扩展，可集成到现有系统）
├─ 💻 Code-as-Action（代码生成+GraalVM 沙箱执行）
├─ 🧠 统一经验体系（COMMON/REACT/TOOL）
├─ 📖 自动学习提取经验（越用越聪明）
├─ 🔔 定时/延迟/事件触发器
├─ 📬 多渠道回复（钉钉/飞书/企微 SPI）
└─ 🏗️ Spring Boot Starter 式集成
```

### 适用场景

| 场景 | 推荐项目 | 原因 |
|------|---------|------|
| **复杂研究任务/报告生成** | **DeepResearch** | 唯一支持端到端研究报告的项目 |
| **企业客服系统** | **SmartAssistant** | 订单查询、商品咨询、认证限流全齐 |
| **需要集成到现有系统** | **AssistantAgent** | Spring Boot Starter + SPI 扩展机制 |
| **数据合规/内网部署** | **SmartAssistant** | 纯本地推理，数据不出内网 |
| **需要 Agent 自动学习** | **AssistantAgent** | 唯一有经验提取和学习能力的项目 |
| **多 Agent 协作场景** | **SmartAssistant** | A2A 协议 + Nacos 动态发现 |
| **代码执行/自动化操作** | **AssistantAgent** | GraalVM 沙箱执行生成的代码 |
| **需要多渠道触达** | **AssistantAgent** | 钉钉/飞书/企微 SPI 扩展 |
| **低延迟高并发客服** | **SmartAssistant** | 三层缓存 + 请求排队 + 限流 |
| **快速验证 AI 编排能力** | **DeepResearch** | 单体启动最快，无需基础设施 |

### 架构思想对比

```
DeepResearch:
研究流程驱动 → 确定性Graph → 强流程控制 → 适合"怎么做"

SmartAssistant:
客服场景驱动 → 微服务A2A → 高可用低延迟 → 适合"怎么稳"

AssistantAgent:
能力复用驱动 → Code生成执行 → 可学习可扩展 → 适合"怎么变"
```

### 三者的互补关系

```
                      ┌──────────────────┐
                      │   AssistantAgent  │
                      │  (框架+学习)      │
                      │  提供:            │
                      │  · 经验/学习体系   │
                      │  · Code-as-Action  │
                      │  · 多渠道/触发器   │
                      └────────┬─────────┘
                               │ 可嵌入
              ┌────────────────┼────────────────┐
              ▼                ▼                 ▼
    ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
    │   DeepResearch   │ │   SmartAssistant │ │   现有业务系统    │
    │  (研究能力)       │ │  (客服能力)       │ │  (业务对接)       │
    │  提供:            │ │  提供:            │ │                   │
    │  · 研究报告       │ │  · 订单/商品      │ │  · 企业知识库     │
    │  · Graph编排      │ │  · 本地推理       │ │  · 业务工具       │
    │  · HITL           │ │  · 缓存/监控      │ │  · 用户体系       │
    └──────────────────┘ └──────────────────┘ └──────────────────┘
```

### SmartAssistant 可以从另外两个项目借鉴什么

| 从 DeepResearch 学习 | 从 AssistantAgent 学习 |
|---------------------|----------------------|
| 引入 Graph 编排替代自研 TaskPlanner | 引入经验体系，实现"越用越聪明" |
| 增加代码执行能力（Docker Sandbox） | 支持多渠道回复（钉钉/飞书等） |
| 引入 HyDe 提升 RAG 精度 | 增加定时/延迟触发器 |
| 增加 HITL 机制 | 改为 Spring Boot Starter 发布方式 |
| 完善知识库管理界面 | 增加 SPI 扩展机制方便集成 |

### 三项目都可以互相学习

```
DeepResearch ← → SmartAssistant
  Graph编排     ← →   微服务A2A  
  研究报告      ← →   客服场景深度
  云端LLM       ← →   纯本地推理
  ES RAG        ← →   pgvector RAG

DeepResearch ← → AssistantAgent
  Graph编排     ← →   Code-as-Action
  HITL          ← →   经验/学习体系
  Docker沙箱    ← →   GraalVM沙箱
  研究报告      ← →   多渠道回复

SmartAssistant ← → AssistantAgent
  三层缓存      ← →   经验复用
  全栈可观测    ← →   OpenTelemetry
  认证限流      ← →   触发器/定时
  JWT防护       ← →   GraalVM沙箱
```
