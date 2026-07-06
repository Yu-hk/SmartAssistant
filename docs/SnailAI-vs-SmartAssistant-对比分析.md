# Snail AI vs SmartAssistant 深度对比分析

> 分析日期：2026-07-06
> 分析方式：基于双方 Gitee/GitHub 源码仓库的完整结构分析

---

## 一、项目定位对比

| 维度 | Snail AI 1.0.0 | SmartAssistant |
|------|---------------|---------------|
| **定位** | 企业级 AI Agent 开源平台 | AI 多智能体客服/助理平台 |
| **目标用户** | Java/Spring 技术栈团队，通用 AI 平台 | 电商/服务平台（订单+商品+通用对话） |
| **团队** | 爱租搭（aizuda）开源社区 | 于海阔，个人项目 |
| **开源协议** | Apache License 2.0 | 未标注（私有项目） |
| **成熟度** | 1.0.0 正式版，6 个月迭代 | 持续开发中，架构多次重构 |

---

## 二、核心技术栈对比

| 技术 | Snail AI | SmartAssistant | 差异分析 |
|------|----------|---------------|---------|
| **Java** | 21 | 21 | 一致 |
| **Spring Boot** | **4.1.0** | **3.4.8** | ⚡ Snail AI 领先一个主版本，采用最新的 Boot 4.x |
| **Spring AI** | 2.0.0 | 2.0.0 | 一致 |
| **Spring Cloud** | 未使用 | **2024.0.0** + Nacos | SmartAssistant 采用微服务全套，Snail AI 单体+grpc |
| **ORM** | MyBatis-Plus 3.5.16 | MyBatis-Plus 3.5.9 | 相近 |
| **前端框架** | **Vue 3 + Ant Design Vue** (SoybeanAdmin) | **React 18 + TDesign** | 技术路线不同 |
| **构建工具** | pnpm monorepo | npm | 不同 |
| **AI 模型接入** | Spring AI 统一，多 Provider | Spring AI + 本地 Ollama | Snail AI 支持更多云端模型 |
| **嵌入模型** | 云端/本地均可（抽象） | 本地 BGE ONNX（独立服务） | SmartAssistant 完全本地化 |
| **gRPC** | **grpc-java 1.76.0** | 未使用 | ⚡ Snail AI 独家技术 |
| **向量数据库** | Milvus 3.x / PGVector / ES | Milvus 2.3.5 / pgvector | 相近 |
| **文档解析** | PDFBox 3.0.7 + POI 5.5.1 | 未深入集成 | Snail AI 有完整 RAG 管道 |
| **消息队列** | gRPC 流 | Redis + SSE | 通信范式不同 |

---

## 三、架构设计对比

### 3.1 整体架构

```
Snail AI (Server-Agent 分离)
┌─────────────────────────────────┐
│  Server 端 (单体应用)            │
│  ├─ Admin REST API              │
│  ├─ OpenAPI REST + SSE          │
│  ├─ Agent 对话责任链             │
│  ├─ RAG 检索管线                 │
│  ├─ 模型管理                     │
│  └─ 持久化抽象层                 │
└──────────────┬──────────────────┘
               │ gRPC 双向流
               ▼
┌─────────────────────────────────┐
│  Agent 客户端 (独立进程, 可扩展)  │
│  ├─ SnailAiInterceptor 链       │
│  ├─ Spring AI Advisor 链        │
│  ├─ Tool Runtime (MCP/Shell/    │
│  │   Http/RAG/Skill/自定义)     │
│  └─ ChatClient → ChatModel      │
└─────────────────────────────────┘

SmartAssistant (微服务架构)
┌─────────────────────────────────┐
│  Gateway (JWT 鉴权 + 路由)       │
├─────────────────────────────────┤
│  Consumer (SSE 入口 + 会话管理)  │
│     ↓ HTTP REST                 │
│  Router (智能路由 + 经验体系)    │
│     ↓ HTTP REST                 │
│  ┌──────┬───────┬──────────┐   │
│  │Order │Product│ General  │   │
│  └──────┴───────┴──────────┘   │
├─────────────────────────────────┤
│  Embedding Service (独立部署)    │
└─────────────────────────────────┘
```

**关键差异**：Snail AI 采用 **Server-Agent gRPC 分离架构**，Server 端是单体（但可按功能拆分），Agent 端是水平可扩展的。SmartAssistant 采用 **纯微服务 HTTP 架构**，每个 Agent 是独立 Spring Boot 服务。

### 3.2 通信机制

| 维度 | Snail AI | SmartAssistant |
|------|----------|---------------|
| **内部通信** | gRPC 双向流 | HTTP REST / Redis |
| **流式传输** | gRPC Stream → SSE | HTTP SSE (Consumer 中转) |
| **服务发现** | 客户端心跳注册 + 六种路由策略 | Nacos + LoadBalancer |
| **负载均衡** | LEAST_LOAD / ROUND_ROBIN / CONSISTENT_HASH / LRU / RANDOM / FIRST | Spring Cloud LoadBalancer |

### 3.3 部署模型

| 维度 | Snail AI | SmartAssistant |
|------|----------|---------------|
| **最少进程数** | 3 (Server + Agent + DB) | 8 (Gateway+Consumer+Router+Order+Product+General+User+Embedding) |
| **Agent 扩展** | 水平扩展 Agent 客户端实例 | 水平扩展各 Agent 微服务 |
| **配置中心** | 无（application.yml） | Nacos |
| **容器化** | Docker Compose | Docker Compose |

---

## 四、Agent 编排机制对比

### 4.1 任务调度

| 维度 | Snail AI | SmartAssistant |
|------|----------|---------------|
| **执行模型** | 服务端责任链 → gRPC → Agent 端执行 | Router LLM 意图识别 → Agent 选择 → HTTP 调用 |
| **多 Agent 协同** | 单 Agent 对话（责任链内嵌 RAG/Tool/Skill） | 多 Agent 路由（意图驱动选择 + 经验体系） |
| **任务拆解** | 无（单轮单 Agent） | IntentGraph DAG 拆解 + 任务规划 |
| **并行执行** | N/A（无 DAG 支持） | DAG 无依赖节点并行执行 |
| **Checkpoint** | gRPC 流天然支持断点 | Redis 缓冲区 + Last-Event-ID 续传 |
| **条件分支** | 无 | 条件边（ConditionalEdge） |
| **人工审批** | 无 | ApprovalService 二次确认 |

### 4.2 责任链设计

| Snail AI 服务端链 | SmartAssistant Router 策略 |
|-------------------|---------------------------|
| InitContext → | 经验匹配 → |
| Conversation → | 语义缓存 → |
| ContextCollector → | LLM 意图识别 → |
| ModelResolve → | 关键词快速路由 → |
| Mcp → | 多维度评分兜底 → |
| Skill → | 调用目标 Agent |
| SystemPrompt → | |
| RAG (强制模式) → | |
| WebSearch → | |
| **LlmCall (gRPC 分发)** | |

**SmartAssistant 在策略层次上更丰富**（经验体系 + 语义缓存 + 多维度评分），而 Snail AI 的链式结构在**执行流程上更精细**（每个步骤独立 Handler）。

---

## 五、RAG 能力对比

| 维度 | Snail AI | SmartAssistant（Order/Product） |
|------|----------|-------------------------------|
| **文档解析** | 10+ 格式（PDF/Word/Excel/PPT/MD/HTML/CSV） | ❌ 无文档解析能力 |
| **分片策略** | 5 种（分隔符/固定长度/正则/智能/Token感知） | ❌ 无 |
| **向量检索** | VectorSearchHandler | BGE ONNX 嵌入 + pgvector/Milvus |
| **BM25 检索** | ✅ BingSearchHandler (ES 实现) | ✅ Product Bm25Scorer (文本评分) |
| **融合策略** | RRF + 加权求和 | ✅ Product RRF 融合 |
| **重排序** | ✅ RerankHandler (调用 rerank model) | ❌ 无 |
| **查询重写** | ✅ QueryRewriteHandler (LLM 重写) | ❌ 无 |
| **去重机制** | SHA-256 内容哈希，4 种策略 | ❌ 无 |
| **Multi-Query** | ❌ 无 | ✅ Product 3 路查询扩展 |
| **图检索** | ❌ 无 | ✅ OrderGraph / ProductGraph |
| **知识库管理** | ✅ 完整管理面板 | ❌ 无管理面板 |

**结论**：Snail AI 的 RAG 能力**全面领先**，从文档导入到分片检索是完整的生产级闭环。SmartAssistant 在 Multi-Query 和 Graph 检索上有自己的特色，但整体 RAG 基础设施薄弱。

---

## 六、工具系统对比

### 6.1 工具发现

| 机制 | Snail AI | SmartAssistant |
|------|----------|---------------|
| @Tool 注解 | ✅ Spring Bean 自动发现 | ✅ 手动注册 |
| MCP 工具 | ✅ 完整支持（SSE/Stdio/Streamable HTTP） | ✅ 部分支持 |
| Shell 执行 | ✅ ShellTool（隔离目录+超时+跨平台） | ✅ ScriptSandbox（三层防御） |
| HTTP 请求 | ✅ HttpTool（自动屏蔽内网/本机） | ❌ 无 |
| 自定义扩展 | CustomToolCallbackProvider | ✅ Spring @Component 注入 |

### 6.2 安全机制

| 机制 | Snail AI | SmartAssistant |
|------|----------|---------------|
| Shell 沙箱 | 目录隔离 + 超时 + 输出截断 | ✅ 三层防御（黑名单+资源限制+虚拟线程超时） |
| SQL 注入防护 | ❌ 无 | ✅ SqlSecurityValidator AST 白名单 |
| HTTP 安全 | ✅ 自动过滤内网/本机地址 | ❌ 无 |
| JWT 鉴权 | ❌（使用 Token 认证） | ✅ Gateway 全局 JWT 白名单 |
| API Key 加密 | ✅ 数据库加密存储 | ✅ 环境变量 |
| 数据脱敏 | ❌ 无 | ✅ DataMaskingService |

---

## 七、前端对比

| 维度 | Snail AI (snail-ai-admin) | SmartAssistant (frontend/) |
|------|--------------------------|---------------------------|
| **框架** | Vue 3 + TypeScript | React 18 + TypeScript |
| **UI 库** | Ant Design Vue 4 | TDesign React 1.x |
| **构建** | Vite 7 | Vite 5 |
| **包管理** | pnpm monorepo | npm |
| **布局** | SoybeanAdmin 模板 | 自定义布局 |
| **管理面板** | ✅ 完整的智能体管理、RAG 管理、模型管理、用户管理 | ✅ Agent配置页面、管理页面 |
| **嵌入聊天** | ✅ snail-ai-chat 独立前端，可 iframe 嵌入 | ✅ ChatPage + CustomerChatPage |
| **主题** | 支持 | 支持（useTheme） |
| **CSS 方案** | UnoCSS | Tailwind CSS |

---

## 八、可观测性对比

| 维度 | Snail AI | SmartAssistant |
|------|----------|---------------|
| **指标采集** | Token 用量采集 + 活跃对话计数 | ✅ Prometheus + Micrometer |
| **可视化** | 管理端 Token 统计 | ✅ Grafana（9 个 Dashboard） |
| **链路追踪** | ❌ 无 | ✅ Jaeger + Zipkin + OpenTelemetry |
| **日志聚合** | ❌ 无 | ✅ Loki + Promtail |
| **告警** | ❌ 无 | ✅ Prometheus + Loki 告警规则 |
| **性能监控** | ❌ 无 | ✅ PerformanceMonitorAspect + Resilience4j |
| **LLM 追踪** | ❌ 无 | ✅ RouterMetricsCollector |

**SmartAssistant 在可观测性方面明显领先**，拥有完整的 Prometheus + Grafana + Jaeger + Loki 监控栈。

---

## 九、数据库与存储对比

| 维度 | Snail AI | SmartAssistant |
|------|----------|---------------|
| **业务库** | MySQL / PostgreSQL / **达梦** / SQL Server | PostgreSQL |
| **多库抽象** | ✅ 模板模式（mysql/postgres/dm 三套实现） | ❌ 仅 pgvector |
| **向量库** | Milvus 3.x / PGVector / ES | Milvus 2.3.5 / pgvector |
| **对象存储** | MinIO / 本地存储 | ❌ 无 |
| **缓存** | Redis | Redis |
| **ORM** | MyBatis-Plus + 代码生成器 | MyBatis-Plus + JPA |
| **表数量** | ~24 张 | ~16 张 |

---

## 十、适用场景对比

| 场景 | Snail AI | SmartAssistant |
|------|----------|---------------|
| **企业内部 AI 助手** | ✅ 强项（完整管理端+Agent 市场） | ❌ 偏客服场景 |
| **智能文档问答** | ✅ 核心能力（完整 RAG 管线） | ❌ 弱 |
| **电商客服** | ⚠️ 通用平台，可定制 | ✅ 核心场景（订单+商品+通用） |
| **代码助手/自动化** | ✅ Skill + ShellTool | ✅ ScriptSandbox |
| **数据不出域** | ✅ 本地 Agent 客户端 | ✅ 全本地部署（BGE + Ollama） |
| **多租户/OpenAPI** | ✅ 完整设计 | ⚠️ 有限支持 |
| **大流量/高并发** | ⚠️ 需水平扩展 Agent | ⚠️ 微服务可水平扩展 |
| **合规/信创** | ✅ 达梦数据库支持 | ⚠️ 仅 PostgreSQL |

---

## 十一、核心差异总结

### Snail AI 的优势

1. **架构成熟度**：Spring Boot 4.x + Server-Agent gRPC 分离，生产级设计
2. **RAG 引擎**：完整的文档解析→分片→混合检索→重排管线，远超 SmartAssistant
3. **多数据库**：MySQL/PostgreSQL/达梦/SQL Server + Milvus/PGVector/ES 三向量库
4. **前端完整度**：智能体市场、RAG 管理、模型管理、Token 统计一应俱全
5. **Client-Server 模式**：工具执行在本地，数据不出域的安全方案设计优雅
6. **Skill 技能包**：SKILL.md 上传执行，扩展性优于 SmartAssistant 的静态工具列表
7. **拦截器机制**：双重拦截（自定义 SPI + Advisor），比 SmartAssistant 的硬编码 filter 更灵活

### SmartAssistant 的优势

1. **智能路由策略**：经验体系（COMMON/REACT/TOOL）→ 语义缓存 → LLM 意图识别 → 关键词路由 → 多维度评分，路径决策链更丰富
2. **多意图 DAG 编排**：IntentGraph 支持任务拆解、并行执行、条件分支、Checkpoint，Snail AI 无此能力
3. **可观测性**：Prometheus + Grafana + Jaeger + Loki 完整监控栈，Snail AI 仅基础 Token 统计
4. **安全体系深度**：ScriptSandbox 三层防御 + SqlSecurityValidator AST 校验 + JWT 全局鉴权 + 数据脱敏
5. **图检索**：OrderGraph/ProductGraph 商品关系图谱，Snail AI 无此能力
6. **本地化程度**：完全本地 BGE ONNX 嵌入 + Ollama 推理，不依赖任何云服务
7. **ApprovalService**：敏感操作二次确认机制，Snail AI 无此设计

### 共同弱项

1. **测试覆盖率**：两个项目测试数量都不足
2. **多模态**：均不支持高级多模态（图片生成/分析为最小可用状态）
3. **国际化**：均以中文为主，无多语言支持
4. **文档完善度**：API 文档和开发者指南都有提升空间

---

## 十二、对 SmartAssistant 的借鉴建议

基于以上对比分析，以下是对 SmartAssistant 未来演进的建议（按优先级排序）：

### P0 — 立即可以借鉴

| 建议 | Snail AI 参考点 | 预期收益 |
|------|----------------|---------|
| **Interceptor/Advisor 机制** | Agent 端双层拦截（SPI + Advisor） | 替代 Gateway 硬编码 Filter，更灵活 |
| **RAG 管线 Pipeline 化** | RagSearchPipeline + 可扩展 Handler | 取代目前 Product 硬编码的 5 路召回 |
| **查询重写** | QueryRewriteHandler | 提升 RAG 检索命中率 |

### P1 — 中期建设

| 建议 | Snail AI 参考点 | 预期收益 |
|------|----------------|---------|
| **文档解析管线** | 10+ 格式解析 + 5 种分片策略 | 补齐 RAG 基础设施短板 |
| **Rerank 重排序** | RerankHandler | 提升检索排序质量 |
| **去重机制** | SHA-256 内容去重 | 避免知识库重复文档 |

### P2 — 长期演进

| 建议 | Snail AI 参考点 | 预期收益 |
|------|----------------|---------|
| **多数据库抽象** | 模板模式适配 MySQL/PostgreSQL/达梦 | 信创合规 |
| **gRPC 替代 HTTP** | gRPC 双向流 | 更高性能的流式通信 |
| **Skill 技能包** | SKILL.md 上传执行 | 更灵活的扩展机制 |

---

## 对比总览表

| 评估维度 | Snail AI | SmartAssistant | 领先方 |
|---------|----------|---------------|--------|
| 架构设计 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | 🐌 Snail AI |
| RAG 能力 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | 🐌 **Snail AI** |
| Agent 编排 | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | 🔧 **SmartAssistant** |
| 前端完整度 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | 🐌 Snail AI |
| 安全体系 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | 🔧 SmartAssistant |
| 工具系统 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | 🐌 Snail AI |
| 可观测性 | ⭐⭐ | ⭐⭐⭐⭐⭐ | 🔧 **SmartAssistant** |
| 多数据库 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | 🐌 Snail AI |
| 本地化程度 | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | 🔧 SmartAssistant |
| 开源成熟度 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | 🐌 Snail AI |

> **总结**：Snail AI 是一个通用性更强的 **AI Agent 基础设施平台**，提供了完整的管理端、RAG 管线、多数据库适配。SmartAssistant 在 **智能路由与编排**、**可观测性**、**安全体系** 方面有其独特优势，更适合做面向特定业务场景（客服）的 AI 中台。两个项目的设计理念不同，但可以互相取长补短。

---

*本文档基于双方项目源码（截至 2026-07-06）分析生成。Snail AI 源码来自 [Gitee: aizuda/snail-ai](https://gitee.com/aizuda/snail-ai)，SmartAssistant 源码来自本地仓库。*
