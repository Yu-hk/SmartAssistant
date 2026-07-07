# RAG 工程化剩余改进点（补齐 6 项短板之后）

> 本文档承接 `docs/rag-seven-questions-analysis.md`（字节 RAG 七连问对照分析）。
> 状态基准：2026-07-07 已补齐 6 项核心短板（待提交），其余改进点见下文。
> 代码证据均基于 `0e89422`/`b42e251` 之后的真实源码 grep 结果。

---

## 0. 已补齐状态（2026-07-07，提交前）

| 短板 | 落地实现 | 测试 |
|------|----------|------|
| 🔴 Q2 PII 脱敏 | `common.rag.ingestion.PiiScrubber`（手机/身份证/邮箱/内部IP/工号正则脱敏） | `PiiScrubberTest` ✅ |
| 🟡 Q2 质量门禁 | `common.rag.ingestion.ChunkQualityScorer`（信息密度+长度+权威性→0~1 分，低质不入库） | `ChunkQualityScorerTest` ✅ |
| 🟡 Q6 权威性排序 | `AuthorityLevel` 枚举 + Milvus/PgVector `composeScore` 加权（L1>L2>L3>L4） | `GovernanceFieldTest` ✅ |
| 🔴 Q7 状态隔离 | `DocumentStatus` 枚举 + search 过滤 `QUARANTINED` + `isRetrievable` 排除 SUPERSEDED | `GovernanceFieldTest` ✅ |
| 🔴 Q3 双栏检测 | `PdfDocumentParser` 基于 x 坐标聚类的 `TwoColumnPdfTextStripper` | PdfParserTest（双栏） |
| 串联 | `KnowledgeIngestionService` 入库前质检 pipeline（脱敏→质量→注入 authority/status） | — |

Common 全量回归：`tests=308, Failures=0, Errors=10`（10 Errors 为预存 Redis 环境错误 `EntityProfileServiceTest`，与本次改动无关）。

---

## 1. RAG 七连问 backlog 尚未落地

| 改进项 | 对应问题 | 当前缺口（代码证据） | 优先级 | 建议实现 | 风险/依赖 |
|--------|----------|----------------------|:----:|----------|-----------|
| **非覆盖式版本 + 入库审核/回滚** | Q7 真正闭环 | ✅ **已落地（2026-07-07）**：`KnowledgeBase` 新增 `markSupersededByBaseId`/`quarantine`/`restore`/`updateStatus`/`listIdsByBaseDocId`；`KnowledgeIngestionService` Step 4.5 改为标记旧版 SUPERSEDED 而非物理删；新增 `IngestAuditEvent`/`IngestAuditRecorder` 审计；`MilvusKnowledgeBase` 修正 `QueryResultsWrapper` API 误用。测试：`KnowledgeVersioningTest`/`KnowledgeIngestionVersioningTest`（10 用例通过） | ✅ 已落地 | — | 已完成 |
| **PDF 表格 / 图片 / OCR 提取** | Q3 | `PdfDocumentParser` 仅 PDFBox 纯文本 + 双栏；grep `pdfplumber`/`Tabula`/`OCR` 无实现（仅注释提及） | 🔴 高 | 引入 pdfplumber/Tabula 或布局模型做表格结构识别；图片 OCR 走外部服务 | 引入新依赖 / 外部服务 |
| **实体关系抽取 + 知识图谱** | Q4/Q5 深化 | `KnowledgeDocument` 元数据仅 6 字段，grep `entityExtract`/`relationExtract`/`知识图谱` 无结果 | 🟡 中 | LLM 抽取实体/关系写入图存储，支撑跨文档推理 | 需图存储或 PG 扩展 |
| **检索侧冲突消解（第二层）** | Q6 | ✅ **已落地（2026-07-07 晚）**：新增 `common.rag.retrieval.CrossDocumentConflictResolver`，rerank 之后对候选 chunk 跨文档冲突检测（复用 `ContextFaithfulnessChecker` 词表），按 AuthorityLevel→版本优先级 判定胜负，败方相对扣分（默认 0.30）、平局双方减半扣分；输出 `ScoreBreakdown` 可观测明细 + `ConflictDecision` 审计；已接入 `InMemoryKnowledgeBase.search`（Stage 4）并经 `KnowledgeBaseConfig` 自动接线。测试 `CrossDocumentConflictResolverTest`（8 用例）✅ | ✅ 已落地 | — | 已接入 InMemory；PgVector/Milvus 暂未接入（与 reranker/trace 同模式，待后续） |
| **chunk 级增量 diff** | Q5 深化 | `ContentHashCache` 文档级；大文档微调需整文档重算 | 🟡 中 | 对 chunk 内容单独哈希，仅重算变更 chunk | 需重构 hash 粒度 |
| **多源结构化分流** | Q1 | 解析后未做结构化抽取（FAQ/表格/流程分流不同索引） | 🟢 低-中 | 解析后按类型路由到不同 collection/索引 | 索引 schema 扩展 |

---

## 2. 本轮新增落地（2026-07-07 下午）

| 改进项 | 优先级 | 落地实现 | 测试 |
|--------|:----:|----------|------|
| **P1-1 PDF 表格提取** | 🔴 高 | `PdfDocumentParser` 新增位置感知表格检测：基于文本 x/y 坐标聚类检测对齐多列多行区域，重构为 Markdown，作为 `contentType=pdf-table` 独立 `ParsedDocument` 输出；正文段落不受影响 | `PdfParserTableTest`（3 用例，用 PDFBox 生成真实表格 PDF 验证）✅ |
| **P1-3 RedisChatMemory Bean 注册** | 🔴 高 | 新增 `ChatMemoryAutoConfiguration`：按 `chat.memory.type`（默认 inmemory / redis）注册 `ChatMemory` Bean；无 Redis 时自动降级 `InMemoryChatMemory`；`@ConditionalOnMissingBean` 允许下游覆盖 | `ChatMemoryAutoConfigurationTest`（4 用例：默认/redis/降级/覆盖）✅ |
| **P1-2 AgentCallerService 结构化提取统一** | 🟡 中 | `AgentCallerService` 注入 `AiChatService` + `lightChatModel`，`callAgentAndExtractTitles` 通过 `entity()` 将 Agent 回复绑定为 `ExtractedTitles`（标题/标签），替代原 no-op；未注入时降级为空，向后兼容 | `AgentCallerTitleExtractionTest`（4 用例）✅ |
| **P2-a chunk 级增量 diff** | 🟡 中 | `ContentHashCache` 新增 chunk 级哈希 API（`putChunk`/`hasChunkChanged`/`diffChunks`）；`KnowledgeIngestionService` Step 4.2 仅对内容变更的 chunk 重新摄入，跳过未变更 chunk 的重复向量化 | `ContentHashCacheChunkTest`（5 用例）✅ |
| **P2-b 检索侧跨文档冲突消解（Q6 第二层）** | 🟡 中 | 新增 `common.rag.retrieval.CrossDocumentConflictResolver`：rerank 后跨文档冲突检测（复用 `ContextFaithfulnessChecker` 词表）→ AuthorityLevel→版本优先级 判定胜负 → 败方相对扣分（默认 0.30）/平局双方减半；输出 `ScoreBreakdown`(base/authority/penalty/final) 可观测 + `ConflictDecision` 审计；接入 `InMemoryKnowledgeBase.search` Stage 4 + `KnowledgeBaseConfig` 自动接线 | `CrossDocumentConflictResolverTest`（8 用例）✅ |

> **验证状态**：common 模块全量回归 `tests=338, Failures=0, Errors=10`（10 Errors 为预存 `EntityProfileServiceTest` Redis 环境错误，与本改动无关）；新增 P0+P1+P2a+P2b 测试共 40 用例全部通过。全模块 `mvn compile` 通过。
> **已知遗留**：router 模块 test 源码集中存在预存的编译错误（`SemanticRouteCacheService`/`RouterService` 构造器签名不匹配），与本轮改动无关，阻塞 router 整体 `mvn test`，但不影响 main 编译与 P1-2 生产代码（已通过隔离测试验证）。检索侧冲突消解当前仅接入 `InMemoryKnowledgeBase`（与 reranker/trace 接线同源模式）；PgVector/Milvus 待后续按同模式接入。

## 3. RAG 七问之外的工程化延伸

- **评测闭环接入 CI**：已有 `RAGEvaluator` / `HallucinationDetector` / `RetrievalMetrics`（`common.rag.eval`），但未接入自动化回归（golden set 跑分门槛卡点）。落地后可防 RAG 质量回归。
- **多模态 RAG**：图片/截图攻略入库（travel 场景截图多）。当前纯文本，缺失视觉内容检索。
- **检索可观测性**：trace 已有，但检索命中/丢弃原因未可视化，调参难。建议检索结果附带 `score_breakdown`（cosine/time/version/authority）。
- **经验体系 × RAG 协同**：Router `ExperienceService` 与 RAG 命中质量未打通，路由决策可利用 RAG 命中率反馈。
- **语义缓存一致性**：Router 语义缓存 vs RAG 检索结果一致性校验，避免缓存返回过期知识。

---

## 4. Spring AI 2.0 工程化遗留收尾（前序会话待办）

| 项 | 现状 | 说明 |
|----|------|------|
| **RedisChatMemory Bean 注册** | 仅 `MessageCodec` 单测 + 编译验证 | grep `new RedisChatMemory`/`@Bean ChatMemory` 无结果，未注册到容器，未做 profile 切换联调 |
| **AgentCallerService entity() 迁移** | `AgentCallerService`（router）存在，仍文本 JSON 解析 | 与已落地的 `OrderIntentService.entity()` / `LLMPreferenceExtractor.entity()` 不一致，应统一 |
| **P3 真实 MCP 接入** | 产品决策，非代码缺陷 | 保留为产品规划项 |

---

## 5. 建议推进路线

```
P0  非覆盖式版本 + 隔离/回滚 API        （让 Q7 真正闭环，错误文档可逆）
  │
P1  ├─ PDF 表格提取（pdfplumber/Tabula）
  │   ├─ AgentCallerService entity() 迁移（统一结构化输出）
  │   └─ RedisChatMemory Bean 注册 + profile 联调
  │
P2  ├─ 实体关系抽取 + 知识图谱
  │   ├─ 评测闭环接入 CI（golden set 门槛）
  │   ├─ 检索侧冲突消解（跨文档权威性）
  │   └─ 多模态 RAG
```

> 优先级判据：P0 解决"错误文档入库不可逆"的生产事故风险；P1 提升解析质量与工程一致性；
> P2 为能力深化，ROI 依业务场景（订单/退款文档多表格 → PDF 表格优先级最高）。
