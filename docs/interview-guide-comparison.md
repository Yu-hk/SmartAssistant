# interview-guide 实现思路分析与 SmartAssistant 对比

> 分析对象：`https://github.com/Snailclimb/interview-guide.git`（浅克隆于 `D:\workspace\interview-guide`）
> 对比基准：本项目 SmartAssistant（AI 多智能体旅行规划平台）
> 分析日期：2026-07-27（首版）；2026-07-27 重分析（本轮）

---

## 〇、本轮重分析说明（先更新本项目再分析）

执行 `git fetch` 后发现：**远程 `phase3-evolution` 无任何新提交**，本地 `9ac98c3`（feat(rag): 父-子分块入库生产改造）已领先远程 1 个提交且包含全部远程内容，故 `git pull` 实质为 **no-op**（远程无代码可合入）。

但本轮重分析发现：**当前工作区存在 17 个未提交改动**，其中大量是上一份报告之后才落地的 RAG 新能力（MinerU 侧车、知识图谱抽取、图注回贴后处理等）。这些本地开发显著改变了对比结论，故重分析重点刷新「RAG / 多模态 / 图谱」维度。

> ⚠️ 处理记录：本仓库本地存在 2 个损坏的 `refs/codex/...` 引用（Codex 工具遗留，指向不存在的对象），曾导致 `git fetch` 全量失败；已用 `git update-ref -d` 清理。另发现本沙箱环境的远程跟踪引用写入异常（fetch 报 "[new branch]" 但引用未落盘），已改用 SHA 直比提交关系绕过。

---

## 一、interview-guide 是什么

一个 **「智能 AI 面试官平台」**，定位为 Spring AI 实战教学/毕设级开源项目。核心能力：简历解析、模拟面试（文字+语音）、面试安排（日历）、知识库 RAG 问答、知识库题库面试、多模型/系统设置。

| 维度 | 选型 |
|------|------|
| 后端 | Spring Boot **4.1** + **Java 25（虚拟线程）** + Spring AI **2.0.0** |
| 构建 | **Gradle 9.6**（`settings.gradle` 单 `app` 模块） |
| 存储 | PostgreSQL 16 + **pgvector**（HNSW, COSINE, dim 1024） |
| 缓存/队列 | Redis + **Redisson 4.0**（Redis Stream 做异步） |
| 前端 | React 18.3 + TS 5.6 + Vite 5.4 + Tailwind 4.1 + React Router 7 |
| AI | OpenAI 兼容模式（DashScope/Kimi/DeepSeek/GLM/LM Studio），spring-ai-agent-utils 0.10 |
| 其他 | Apache Tika 解析、iText 8 导出 PDF、DashScope SDK（Qwen3 ASR/TTS）、AWS S3 SDK（RustFS）、MapStruct、SpringDoc |

---

## 二、interview-guide 核心实现思路（附证据）

### 1. 模块化单体（Modular Monolith）
不是微服务，而是单个 Spring Boot 应用内按业务域分包：`common`（ai/annotation/aspect/async/config/evaluation/exception/result/transaction）+ `infrastructure`（export/file/mapper/redis）+ `modules/*`（interview / interviewschedule / knowledgebase / llmprovider / resume / voiceinterview）。每个 module 内 `model/repository/service/controller/listener/skill` 职责清晰。
- 证据：`app/src/main/java/interview/guide/modules/...`

### 2. 多 Provider LLM 注册表 + Advisor 链（AI 集成层亮点）
`LlmProviderRegistry` 用 `ConcurrentHashMap` 缓存，按 providerId 动态构造 `ChatClient`，并区分三种变体：
- `getChatClient`（挂 SkillsTool，通用 Agent）
- `getPlainChatClient`（无工具，纯结构化输出，避免工具调用污染 JSON）
- `getVoiceChatClient`（SkillsTool + ToolCallAdvisor，流式语音）
- Advisor 链：`SafeGuardAdvisor`（敏感词护栏）+ `ToolCallingAdvisor` + `MessageWindowChatMemoryAdvisor`，可配置开关。
- API Key 加密落盘（`ApiKeyEncryptionService`），默认写到 `~/.interview-guide/`。
- 证据：`common/ai/LlmProviderRegistry.java`、`AgentUtilsConfiguration.java`、`PromptSecurityConstants.java`

### 3. RAG：pgvector + 分块 + 查询改写 + 动态 TopK + 流式探测
- 分块：`TokenTextSplitter`（约 800 token/块，标点边界，无重叠）。
- 检索：`VectorStore.similaritySearch(SearchRequest)`，按 `kb_id` 元数据 `filterExpression` 过滤，支持 `similarityThreshold`；pgvector 前置过滤失败自动回退本地过滤。
- **查询改写**：`rewriteQuestion` 用 LLM 对短 query 改写，多候选（改写+原句）依次检索，命中即返回。
- **动态 TopK**：按 query 长度分 short/medium/long 三档（topK 20/12/8，阈值 0.18/0.28/0.28）。
- **流式探测短路**：`normalizeStreamOutput` 先缓冲前 120 字符，识别「无信息」模板则立即替换为固定话术并结束，避免长篇拒答。
- **向量化任务原子性**：`pending:kbId:jobId` 临时元数据 → 分批写入（DashScope 限 batch≤10）→ `activateVectorJob` 删除旧向量并 promote；失败 `cleanupPendingVectorJob` 补偿。
- 证据：`modules/knowledgebase/service/KnowledgeBaseVectorService.java`、`KnowledgeBaseQueryService.java`

### 4. Skill 文件化出题（领域知识注入范式）
面试方向以 `resources/skills/{skillId}/SKILL.md`（front matter + persona）+ `skill.meta.yml`（categories + ref 引用）+ `references/*.md`（知识点）定义。`InterviewSkillService` 启动时扫描解析，按 category `priority`（ALWAYS_ONE/CORE/NORMAL）做题目配额分配（`calculateAllocation`），并把 references 注入出题/评估 Prompt。支持 JD 解析自动生成「自定义 Skill」。
- 证据：`modules/interview/skill/InterviewSkillService.java`、`resources/skills/java-backend/skill.meta.yml`

### 5. 异步任务：Redis Stream（解耦重活）
简历分析、知识库向量化、题目生成、面试评估均通过 `*StreamProducer`/`*StreamConsumer` + `QuestionGenerationRecoveryScheduler` 异步执行，前端可查进度（待分析/分析中/已完成/失败），含重试与恢复。
- 证据：`modules/knowledgebase/listener/*`、`modules/interview/listener/*`

### 6. 统一评估引擎 + 结构化输出自愈
- `common/evaluation/UnifiedEvaluationService` 被文字面试与语音面试共用（分批评估 + 结构化输出 + 二次汇总 + 降级兜底）。
- `StructuredOutputInvoker` 封装 `BeanOutputConverter` 调用：**失败自动重试**（默认 2 次），重试时注入上次错误原因 + 严格 JSON 指令，并本地修复未转义引号（`repairUnescapedQuotesInJsonStrings`），带 Micrometer 指标。
- 证据：`common/ai/StructuredOutputInvoker.java`、`modules/interview/service/AnswerEvaluationService.java`

### 7. 语音面试（差异化能力）
WebSocket + 千问3 实时 ASR/TTS（服务端 VAD 断句、句子级并发 TTS、回声防护、暂停/恢复、Micrometer 埋点）。这是 SmartAssistant 完全没有的方向。
- 证据：`modules/voiceinterview/*`、`application.yml` 的 `voice-interview` 配置段。

### 8. 工程化成熟度
Flyway 管理 schema（`ddl-auto: validate`）、Prometheus 指标暴露、注解式限流（Global/IP/User）、API Key 加密、Prompt 注入防御常量、Docker Compose 一键部署、Swagger。教学项目但工程意识强。

---

## 二-B、SmartAssistant 工作区新进展（未提交，本轮重分析确认）

上一版对比将 MinerU / Parent-Child 记为「规划/讨论」。本轮读取工作区代码确认它们**已实际落地（未提交）**，并新增知识图谱抽取：

### B1. MinerU 侧车式 PDF 解析路由（已实现）
- `PdfParserRouter`：文件级预扫描决策「PDFBox 主、MinerU 补」。数字 PDF（有文本、无图或 `routeOnImages=false`）走 PDFBox（零子进程）；含图扫描件 / 复杂混排路由到 MinerU。
- `MinerUProperties`（`app.rag.mineru.*`）：总开关默认关、sidecar 命令、进程池预热、超时、失败回退 PDFBox、caption 独占（R5）等安全默认值。
- `mineru_sidecar.py`：常驻 sidecar，stdin 逐行读 JSON → 调 `magic-pdf` → 归一化 `text/table/image` 块（含 `image_caption` + OCR 文本）写回 stdout。v1 进程协议，预留 gRPC 升级位。
- 差异：interview-guide 仅依赖 `TokenTextSplitter` + 上游解析，**无 MinerU、无版面 / OCR 增强**，对扫描件 / 复杂表格 PDF 召回明显弱于本项目。

### B2. 知识图谱实体 / 关系抽取（已实现）
- `RagGraphAutoConfiguration` + `KnowledgeGraphService` + `LlmEntityExtractor`：摄入流程联动 LLM 抽取实体 / 关系构建图谱（`@ConditionalOnMissingBean` 让位自定义）。
- 差异：interview-guide **无知识图谱层**，RAG 仅向量相似度。

### B3. 父-子分块 + 图注回贴后处理（已实现）
- 父-子分块（提交 `9ac98c3`）：父块不嵌入、子块检索回链整块（见第三节对比）。
- `DocumentChunker.reattachOrphanedFigureCaptions`：正则识别「正文引用图（如下图所示）→ 下块孤立图注」被分页切断的情形，把图注回贴到引用它的上一块，重建 `KnowledgeDocument`（content final）。
- 差异：interview-guide 的 `TokenTextSplitter` 为扁平分块、无图注修复。

### B4. 多模态入库（已实现）
- `OllamaVisionImageCaptioner` / `OcrStrategies` / `OllamaVisionOcrStrategy`：图片 caption + OCR 经本地 OllamaVision 入库。interview-guide 无此能力。

> 结论：在「RAG 解析深度 / 多模态 / 知识图谱 / 父-子分块」四个子维度，SmartAssistant 当前工作区**已全面领先** interview-guide；差距收敛到「多厂商抽象、结构化输出自愈、Skill 文件化、语音面试、开箱运维」五个 interview-guide 领先项。

---

## 三、与 SmartAssistant 的维度对比

| 维度 | interview-guide | SmartAssistant | 差异判断 |
|------|----------------|----------------|----------|
| **架构风格** | 模块化单体（单应用分包） | 微服务（8+ Spring Boot + Gateway + Nacos + Spring Cloud） | 截然相反：单体运维简单、无网络跳数；微服务隔离/独立扩缩，但基础设施复杂 |
| **技术栈版本** | Boot 4.1 / Java 25 / Spring AI **2.0.0** / Gradle | Boot 3.5.16 / Java 21 / Spring AI **1.0.9**（由 2.0.0 降级）/ Maven | **关键分叉**：见第四节 |
| **AI 集成层** | `LlmProviderRegistry` 多厂商抽象 + 3 种 ChatClient 变体 + Advisor 链 + Key 加密 | 本地 Ollama `deepseek-r1:7b` + `TieredModelRouter`（分层路由）+ `lightChatModel`（委托小模型通道）+ embedding-service(BGE) | 对方重「厂商解耦」；本项目重「本地私有化 + 分层模型」 |
| **RAG 分块** | TokenTextSplitter（800 token，无重叠，扁平） | PDFBox/MinerU 解析路由 + **Parent-Child 分块**（父块不嵌、子块回链）+ **图注回贴后处理** | 本项目分块面向版面结构、修复分页切断，质量更高（MinerU 本轮已落地） |
| **RAG 检索** | 向量 + 查询改写 + 动态 TopK + 阈值 + kb_id 过滤 | 向量 + **IntentGuidedQueryRewriter** + **BgeReranker/AdaptiveRerankTopK** + BM25 + ACL | 本项目检索链路**更重**（重排+意图改写+ACL） |
| **RAG 存储** | 仅 pgvector | pgvector **+ Milvus** + InMemory 兜底（`TieredKnowledgeBase`/`ResilientKnowledgeBase`） | 本项目多向量后端、韧性更强 |
| **知识图谱** | 无（仅向量相似度） | `KnowledgeGraphService` + `LlmEntityExtractor`（摄入期 LLM 抽取实体/关系） | **本项目独有**（本轮确认已落地） |
| **多模态** | 无（纯文本 RAG） | `OllamaVisionImageCaptioner`/`OcrStrategies` 多模态入库 | 本项目独有 |
| **合规/安全护栏** | `SafeGuardAdvisor`（敏感词） + 注入防御常量 | `SafeGuardAdvisor` + `ComplianceGuard/Grader` + `PromptInjectionBlockedException` + `AgentSafetyService` + `LoopGuardService` | 本项目护栏**更体系化**（合规打分+注入异常+循环守卫） |
| **Agent/Skill 范式** | Skill 文件（SKILL.md+meta+references）注入领域知识 | Router 意图→Agent + Graph 编排（LangGraph4j）+ 经验体系（ExperienceService）+ Tool Registry（规划） | 对方重「知识注入型 Skill」；本项目重「多 Agent 编排与路由」 |
| **异步模型** | Redis Stream（Producer/Consumer + 恢复调度） | 微服务 + Consumer 统一 SSE 入口 + Redis 阻塞读 5s | 对方解耦更彻底；本项目偏请求驱动 |
| **评估能力** | 统一评估引擎（文字/语音共用） | 旅行领域，`EvaluationReportService` 偏 RAG 评估，无面试评分 | 对方评估针对其领域更成熟 |
| **语音** | 完整语音面试（ASR/TTS/VAD） | 无 | 对方独有 |
| **可观测性** | Prometheus + Micrometer（结构化输出/语音指标） | Grafana + TokenUsageAdvisor + AiAuditEvent | 各有侧重 |
| **运维部署** | Flyway + Docker Compose + 注解限流 + 加密配置 | Nacos + 多模块 Docker + 脚本 | 对方开箱部署更友好 |
| **领域** | AI 面试官（招聘/求职） | AI 旅行规划（多智能体） | 不同赛道 |

---

## 四、关键架构分叉点：Spring AI 2.0.0 的取舍

两者都从 Spring AI 2.0.0 出发，但走向不同：

- **interview-guide**：选择 **全量升级** —— Spring Boot 4.1 + Java 25 + Spring AI 2.0.0 三位一体，因此能直接用 `OllamaOptions`/`OpenAiChatOptions.builder()` 等新 API 与 `spring-ai-agent-utils` 的 `SkillsTool`。
- **SmartAssistant**：实测发现 Spring AI 2.0.0 的 `JsonSchemaGenerator` 强依赖 `org.springframework.core.Nullness`（仅存在于 Spring 7 / Boot 4.0），而 Boot 3.5 解析到 Spring 6.2.19 **不含**该类 → `NoClassDefFoundError`，且任何 `MethodToolCallbackProvider` 构造崩溃。**决策：降级 Spring AI 2.0.0 → 1.0.9**（对齐 Spring 6.2 / Boot 3.5，GA 稳定），保留已升的 Boot 3.5 / Spring Cloud 2025。

> 结论：这是两种合理策略。interview-guide 用「升级整个栈」换取 Spring AI 2.0 新能力；SmartAssistant 用「降级 AI 框架」换取栈的稳定性与本地模型（Ollama）兼容性。前者代价是 Java 25/Boot 4 新栈的适配风险，后者代价是放弃 Spring AI 2.0 的 `builder()`/结构化 API 与 agent-utils 生态。

---

## 五、互相可借鉴点

### SmartAssistant 可向 interview-guide 借鉴
1. **多 Provider 注册表 + Advisor 链抽象**：把「厂商/模型」与业务解耦，`ConcurrentHashMap` 缓存 ChatClient，按场景（agent/结构化/流式）提供变体——比当前 `TieredModelRouter` 更贴近「用户可配置多厂商」诉求。
2. **结构化输出自愈**：`StructuredOutputInvoker` 的「重试 + 注入上次错误 + 本地修复未转义引号 + 指标」可直接复用到本项目的 itinerary/结构化抽取场景。
3. **Skill 文件化领域知识**：将旅行领域知识（景点/交通/签证规则）以 `SKILL.md + meta + references` 管理，比散落 Prompt 更易维护、可热加载。
4. **Redis Stream 异步**：重任务（知识库向量化、长文生成）用 Stream + 恢复调度替代「微服务 + Redis 阻塞」，进度可观测、失败可恢复。
5. **API Key 加密落盘 + 注解限流**：提升多租户/生产安全性。
6. **Docker Compose 一键部署**：降低本地体验门槛。

### interview-guide 可向 SmartAssistant 借鉴
1. **检索增强链路**：引入 Reranker（BGE）+ 意图引导改写 + BM25 混合 + ACL，可显著提升 RAG 准确率（对方目前仅向量 + 简单改写）。
2. **多向量后端与韧性**：`TieredKnowledgeBase`/`ResilientKnowledgeBase` + Milvus 兜底思路，适合大体量知识库。
3. **合规护栏体系**：`ComplianceGuard/Grader` + 注入异常 + 循环守卫，对「用户上传简历/JD」场景同样必要（防 Prompt 注入篡改评估）。
4. **Parent-Child 分块 + 图注回贴 + MinerU 解析**：本项目已实现，可整体借鉴以提升 PDF/扫描件简历解析质量（其中 MinerU 侧车与图注回贴为本项目独有后处理）。
5. **知识图谱实体/关系抽取**：摄入期构建图谱，适合面试知识的结构化关联检索。
6. **Graph 编排 + 经验体系**：若未来扩展多 Agent 协作（如面试官+考官+记录员），LangGraph4j 思路可参考。

---

## 六、总体结论

- **同源性**：两者都是「Spring AI + pgvector + Redis + React/TS」的国产实战项目，都实现了 `SafeGuardAdvisor`、RAG、SSE 流式、结构化输出，说明该技术组合在国内 AI 应用层已成主流基线。
- **本质差异**：interview-guide 是**单体 + 多厂商 + 领域 Skill + 语音 + 教学级工程化**；SmartAssistant 是**微服务 + 本地模型 + 多 Agent 编排 + 重检索/合规/多模态/图谱**的架构展示型平台。
- **本轮重分析后的平衡**：在「RAG 解析深度（MinerU）/ 多模态 / 知识图谱 / 父-子分块」四个子维度，SmartAssistant 当前工作区已**全面领先**；差距收敛到 interview-guide 的五个领先项——**多厂商抽象、结构化输出自愈、Skill 文件化、语音面试、开箱运维**。
- **最值得本项目吸收的**：多 Provider 注册表抽象、结构化输出自愈重试、Skill 文件化知识管理、Redis Stream 异步范式——这四项落地成本低、收益高，且与现有 `common` 模块（已有 `SafeGuardAdvisor`/`TieredModelRouter`/`lightChatModel`）风格兼容。
- **风险对照**：本项目的 Spring AI 1.0.9 降级是已验证的稳妥选择；若未来要追 Spring AI 2.0 能力，需整体评估升级到 Boot 4 / Java 25（即 interview-guide 路线）的成本，而非局部升级。

---

## 附：关键文件索引

### interview-guide（对比对象）
| 关注点 | 文件 |
|--------|------|
| 多 Provider 注册表 | `app/.../common/ai/LlmProviderRegistry.java` |
| Advisor/护栏 | `app/.../common/ai/AgentUtilsConfiguration.java`、`PromptSecurityConstants.java` |
| RAG 检索 | `app/.../modules/knowledgebase/service/KnowledgeBaseQueryService.java` |
| RAG 向量化 | `app/.../modules/knowledgebase/service/KnowledgeBaseVectorService.java` |
| Skill 出题 | `app/.../modules/interview/skill/InterviewSkillService.java` |
| 结构化输出 | `app/.../common/ai/StructuredOutputInvoker.java` |
| 统一评估 | `app/.../modules/interview/service/AnswerEvaluationService.java` |
| 依赖与版本 | `app/build.gradle` |
| 全局配置 | `app/src/main/resources/application.yml` |
| Skill 定义样例 | `app/src/main/resources/skills/java-backend/skill.meta.yml` |

### SmartAssistant（本项目，含未提交新进展）
| 关注点 | 文件 |
|--------|------|
| MinerU 解析路由 | `smart-assistant-common/.../rag/document/mineru/PdfParserRouter.java` |
| MinerU 配置 | `smart-assistant-common/.../rag/document/mineru/MinerUProperties.java` |
| MinerU sidecar | `smart-assistant-common/src/main/resources/mineru/mineru_sidecar.py` |
| 知识图谱自动配置 | `smart-assistant-common/.../rag/graph/RagGraphAutoConfiguration.java` |
| 父-子分块 + 图注回贴 | `smart-assistant-common/.../rag/chunking/DocumentChunker.java` |
| 上下文压缩 | `smart-assistant-common/.../agent/ContextCompressor.java` |
| 轻量模型通道 | `smart-assistant-consumer/.../config/LightChatModelConfig.java` |
| 多模态 caption/OCR | `smart-assistant-common/.../rag/multimodal/OllamaVisionImageCaptioner.java`、`.../rag/document/OcrStrategies.java` |
