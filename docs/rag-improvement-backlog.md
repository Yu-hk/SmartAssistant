# RAG 工程化改进点落地总览

> 本文档承接 `docs/rag-seven-questions-analysis.md`（字节 RAG 七连问对照分析）。
> 状态基准：2026-07-07，RAG 七连问 + 工程化延伸 **全部核心项已落地**，仅剩产品决策项（P3 真实 MCP）与部署激活说明。
> 代码证据均基于真实源码 grep + 单测结果；提交哈希见各节。

---

## 0. 落地总览（一表看清）

| 对应问题 | 落地实现 | 提交 | 测试 |
|----------|----------|------|------|
| 🔴 Q7 非覆盖式版本 + 隔离/回滚 | `KnowledgeBase.markSupersededByBaseId`/`quarantine`/`restore`/`updateStatus`；`KnowledgeIngestionService` Step 4.5 标记 SUPERSEDED；`IngestAuditEvent`/`IngestAuditRecorder` 审计 | `2cad3f8` | `KnowledgeVersioningTest`/`KnowledgeIngestionVersioningTest`（10）✅ |
| 🔴 Q2 PII 脱敏 | `common.rag.ingestion.PiiScrubber` | `d5426b7` 前 | `PiiScrubberTest` ✅ |
| 🟡 Q2 质量门禁 | `ChunkQualityScorer`（密度+长度+权威性→0~1，低质不入库） | `d5426b7` 前 | `ChunkQualityScorerTest` ✅ |
| 🟡 Q6 权威性排序 | `AuthorityLevel` + 各 KB `composeScore` 加权（L1>L2>L3>L4） | `d5426b7` 前 | `GovernanceFieldTest` ✅ |
| 🔴 Q7 状态隔离 | `DocumentStatus` + search 过滤 `QUARANTINED` + `isRetrievable` 排除 SUPERSEDED | `d5426b7` 前 | `GovernanceFieldTest` ✅ |
| 🔴 Q3 双栏检测 | `PdfDocumentParser` 基于 x 坐标聚类的双栏剥离 | `d5426b7` 前 | `PdfParserTest`（双栏）✅ |
| 🔴 Q3 PDF 表格提取 | 位置感知表格检测 → Markdown，`contentType=pdf-table` 独立 `ParsedDocument` | `d5426b7` 前 | `PdfParserTableTest`（3）✅ |
| 🟡 Q3 图片 OCR 提取 | `OcrStrategy`/`NoopOcrStrategy` + `TesseractOcrStrategy`（零新依赖）+ `OcrStrategies.autoDetect()`；`PdfDocumentParser` 默认启用自动检测 | `11a7b56` | `TesseractOcrStrategyTest`（3）✅ |
| 🟡 Q4/Q5 实体关系抽取 + 知识图谱 | `graph` 包：`EntityNode`/`EntityRelation`/`EntityExtractor`/`NoopEntityExtractor`/`KnowledgeGraphService`；`LlmEntityExtractor` 复用 `AiChatService.entity`；`RagGraphAutoConfiguration` 按 `ChatModel` 自动装配 | `11a7b56` | `LlmEntityExtractorTest`（4）✅ |
| 🟡 Q6 检索侧冲突消解（第二层） | `CrossDocumentConflictResolver`（复用 `ContextFaithfulnessChecker` 词表，AuthorityLevel→版本判定，败方扣分 0.30/平局减半，输出 `ScoreBreakdown`+`ConflictDecision`）；已接入 **InMemory + PgVector + Milvus** 三套 KB | `d5426b7` 前 + 本轮 | `CrossDocumentConflictResolverTest`（8）+ `KnowledgeBaseConflictResolverTest`（4）✅ |
| 🟡 Q5 深化 chunk 级增量 diff | `ContentHashCache` chunk 级哈希；`KnowledgeIngestionService` Step 4.2 仅重算变更 chunk | `d5426b7` 前 | `ContentHashCacheChunkTest`（5）✅ |
| 🟢 Q1 多源结构化分流 | `KnowledgeIngestionService` 按 `contentType`（pdf-table/html/word）分流，结构化类型跳过 chunking 直接入库 | `d5426b7` | — |
| 🟡 评测闭环接入 CI | `EvalGate` 四件套 + `GoldenSuiteEvalGate` + `.github/workflows/eval-gate.yml`（绝对阈值+基线回归双保险） | `dede9bb` | `EvalGateTest`（4）+ `GoldenSuiteEvalGateTest`（1）✅ |
| 🟡 多模态 RAG | `multimodal` 包：`ImageReference`/`ImageCaptioner`/`NoopImageCaptioner`/`OllamaVisionImageCaptioner`（Spring AI 2.0 多模态 `ChatClient.media`）/`ImageCaptioners`/`MultimodalIngestor`；`RagMultimodalAutoConfiguration` 按 `rag.multimodal.vision.enabled` 装配；`KnowledgeIngestionService.ingestImages` 联动 | 本轮 | `OllamaVisionImageCaptionerTest`（4）+ `MultimodalIngestorTest`（3）+ `KnowledgeIngestionMultimodalTest`（2）✅ |
| 🟡 检索可观测性 | `RetrievalTrace` 新增 `ScoreBreakdown`；`InMemoryKnowledgeBase` Stage 4 将冲突消解明细写入 trace | `d5426b7` | — |
| 🟡 语义缓存一致性 | `CacheVersionManager` + `CachedRouteDecision.cacheVersionAtSave`；`SemanticRouteCacheService` 写入记版本、读取验版本 | `d5426b7` | — |
| 🟡 经验体系 × RAG 协同 | `ExperienceService` 注入 `InMemoryKnowledgeBase`，`match()` 中 `computeRagQualityFactor`（Top-1 分→0.5~1.0）影响置信度衰减 | `d5426b7` | — |
| 🔴 Router 测试编译修复 | `SemanticRouteCacheService` 删死参数 `lightChatModel` + 对 `aiChatService`/`cacheVersionManager` null 安全；`RouterService` 的 `routingToolChecker`/`degradationService` 标 `@Autowired(required=false)` + null 守卫；补齐 9+2 处测试调用 | `11a7b56` | router `test-compile` BUILD SUCCESS ✅ |
| 🟡 P3 真实 MCP 接入 | **未做（产品决策项，非代码缺陷）** | — | — |

> **验证状态**：common 模块相关单测全绿；router 模块 `test-compile` BUILD SUCCESS（原预存编译错误已修复）；全模块 `mvn compile` 通过。

---

## 1. 本轮新增落地（2026-07-07 晚，待提交）

### 1.1 检索侧冲突消解扩展到 PgVector / Milvus（Q6 第二层收口）
- **PgVectorKnowledgeBase**：`search()` 末尾调用 `applyConflictResolution(conflictResolver, hits)`；新增 `setConflictResolver` + package-private `applyConflictResolution` 静态方法。
- **MilvusKnowledgeBase**：同上（原仅 InMemory 接入，本轮补齐缺失的 setter 与静态方法，此前处于**无法编译**状态，现已修复）。
- **自动接线**：`OrderKnowledgeConfig` 的 `orderKnowledgeBase`（InMemory 种子库）与 `orderMilvusKnowledgeBase` 均通过 `ObjectProvider<CrossDocumentConflictResolver>` 自动挂接冲突消解器；`KnowledgeBaseConfig` 公共 `InMemoryKnowledgeBase` 早前已接。
- **测试**：`KnowledgeBaseConflictResolverTest`（4 用例）直接调用两个 KB 的静态方法，验证「高权威胜出、低权威败方按 0.30 扣分」；null 消解器原样透传。

### 1.2 多模态 RAG（图片 → 视觉描述 → 入库）
- **`common.rag.multimodal` 包**：
  - `ImageReference`（图片字节 + MIME）、`ImageCaptioner` 接口、`NoopImageCaptioner`（默认降级）。
  - `OllamaVisionImageCaptioner`：Spring AI 2.0 多模态——`Media.builder().data(ByteArrayResource).mimeType(MimeType)` + `ChatClient.prompt().user(u -> u.media(...))`；实际调用抽至 `doCaption(...)` 受保护方法便于单测桩接；异常隔离返回空串。
  - `ImageCaptioners.autoDetect(ChatModel)` 工厂 + `MultimodalIngestor`（图片→描述→`KnowledgeDocument` 入库，docId 由字节 SHA-256 派生保证幂等）。
- **装配**：`RagMultimodalAutoConfiguration` 按 `rag.multimodal.vision.enabled=true` 且存在 `ChatModel` Bean 装配真实描述器，否则降级 `NoopImageCaptioner`（保证 Bean 始终存在）。
- **摄取联动**：`KnowledgeIngestionService.setImageCaptioner(...)` + `ingestImages(...)`，成功后触发缓存失效 + 索引重建，与文本摄取最终态一致。
- **测试**：`OllamaVisionImageCaptionerTest`（4）/ `MultimodalIngestorTest`（3）/ `KnowledgeIngestionMultimodalTest`（2）全通过。

---

## 2. 历史已落地（摘要，含提交哈希）

| 提交 | 内容 |
|------|------|
| `2cad3f8` | P0 非覆盖式版本 + 隔离/回滚 API 闭环 |
| `dede9bb` | 评测闭环接入 CI（GoldenSuite + EvalGate + GitHub Actions） |
| `d5426b7` | 检索可观测性（RetrievalTrace ScoreBreakdown）、语义缓存一致性（CacheVersionManager）、多源结构化分流、经验×RAG 协同（ragQualityFactor）、OCR 框架 + 实体关系框架（graph 包） |
| `11a7b56` | Router 测试编译修复、实体关系 LLM 抽取器（LlmEntityExtractor + RagGraphAutoConfiguration）、Tesseract OCR 引擎（零新依赖） |

---

## 3. 剩余项与部署激活说明

| 项 | 状态 | 说明 |
|----|------|------|
| **P3 真实 MCP 接入** | 产品决策项 | 非代码缺陷，保留为产品规划项。 |
| **实体关系图谱持久化** | 当前为内存实现 | `KnowledgeGraphService` 内存实现已可用；PG 邻接表 DDL 已备，跨重启持久化需在装配点接真实数据源（可选增强）。 |
| **OCR 激活** | 代码就绪，需部署安装 | 部署侧 `apt install tesseract-ocr`（中文加 `tesseract-ocr-chi-sim`）。未安装时 `OcrStrategies.autoDetect()` 自动降级 `Noop`，PDF 解析零开销。 |
| **多模态 RAG 激活** | 代码就绪，需配置视觉模型 | `application.yml` 置 `rag.multimodal.vision.enabled=true` 并提供视觉能力 `ChatModel` Bean（如 Ollama `llava`/`qwen2-vl`）。未配置时 `NoopImageCaptioner` 降级，文本 RAG 不受影响。 |
| **实体抽取激活** | 代码就绪，需装配点挂接 | 应用注入 `KnowledgeGraphService` Bean 后调用 `ingestionService.setKnowledgeGraphService(graphService)` 即联动（`KnowledgeWatcherService` 监听目录摄入时自动构建图谱）。 |

---

## 4. 推进路线（最终状态）

```
P0  非覆盖式版本 + 隔离/回滚 API        ✅ 已落地 (2cad3f8)
  │
P1  ├─ PDF 表格提取                     ✅ 已落地
  │   ├─ AgentCallerService entity()    ✅ 已落地 (P1-2)
  │   └─ RedisChatMemory Bean 注册       ✅ 已落地 (P1-3)
  │
P2  ├─ 实体关系抽取 + 知识图谱            ✅ 已落地 (11a7b56)
  │   ├─ 评测闭环接入 CI                  ✅ 已落地 (dede9bb)
  │   ├─ 检索侧冲突消解（跨文档权威性）   ✅ 已落地（InMemory+PgVector+Milvus，本轮收口）
  │   ├─ 多模态 RAG                      ✅ 已落地（本轮）
  │   ├─ OCR 真实引擎                    ✅ 已落地 (11a7b56)
  │   ├─ 检索可观测性                    ✅ 已落地 (d5426b7)
  │   ├─ 语义缓存一致性                  ✅ 已落地 (d5426b7)
  │   └─ 经验 × RAG 协同                 ✅ 已落地 (d5426b7)
  │
P3  真实 MCP 接入                        ⏸ 产品决策项（保留）
```

> RAG 七连问（数据源→清洗→PDF解析→知识抽取→增量更新→冲突处理→版本治理）+ 工程化延伸（评测闭环/多模态/可观测性/缓存一致性/经验协同）**全部核心项已完成**。
> 仅 P3 真实 MCP 接入为产品决策项，与代码缺陷无关。
