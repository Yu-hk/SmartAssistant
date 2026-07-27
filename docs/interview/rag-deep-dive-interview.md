# SmartAssistant RAG 专题 · 面试问答深度版（可直接背）

> 用途：配合 `docs/interview/ai-agent-interview-5d-qanda.md` 的第③节，把 RAG 这一最强差异点讲透。
> 所有回答均锚定本项目真实实现（类名 / 文件 / 关键常量），面试时用自己的话串成 1–2 分钟口语即可。
> 标记 `{占位}` 处按需替换。

---

## 0. 一句话定位（开场钩子）

> "我的 RAG 不是调一个 `vectorStore.similaritySearch()` 就完了。它是一条**可编排的 Pipeline**：摄入侧有分块/PII脱敏/版本治理/脏数据拦截，检索侧有**多路召回 + RRF 融合 + Cross-Encoder 重排 + 质量拒答**，全链路有 trace 和评估。向量库支持内存/PgVector/Milvus 三态切换和运行时降级。"

---

## 1. 架构全景（记这张图）

```
┌─────────────────── 摄入侧 KnowledgeIngestionService ───────────────────┐
│ 解析(DocumentParseRouter: PDF/Word/Html/Txt/Md)                         │
│   → 多模态(MultimodalIngestor + ImageCaptioner 视觉描述)                │
│   → 分块(RecursiveChunkStrategy 递归切分 / ParentChild 双粒度)          │
│   → 质检护栏(PiiScrubber 脱敏 / ChunkQualityScorer / DocumentValidator  │
│              + ReviewQueueService 脏数据拦截 / MetadataEnricher 元数据)  │
│   → 变更检测(ContentHashCache 跳过重算)                                 │
│   → 嵌入(BgeEmbeddingModel 本地ONNX / 远程EmbeddingClient 条件切换)     │
│   → 入库(KnowledgeBase: InMemory / PgVectorStore / Milvus, 增量upsert)  │
│   → 版本治理(IngestionJob + indexVersion) + 审计(IngestAuditRecorder)   │
│   → 图谱联动(KnowledgeGraphService LLM抽实体关系, LightRAG式)           │
└───────────────────────────────────────────────────────────────────────┘
                              ↓ 检索
┌─────────────────── 检索侧 RagSearchPipeline（按 Order 执行）────────────┐
│ MultiQuery(0) → QueryRewrite(2) → AdaptiveWeight(3)                    │
│   → ExactMatch(10) → Keyword(20) → BM25(30)                            │
│   → Knowledge向量(40) → Graph商品图(50) → LightRAG实体图(60)            │
│   → RrfFusionHandler(100): 多路 RRF 融合 + 质量评分                    │
│   → RerankHandler(110): Cross-Encoder 二次精排 + 自适应 Top-K          │
│ 异常分级: Handler 抛错 → PipelineError(handler/code/msg/ts) → 标记     │
│           degraded, 不中断其它路径                                       │
└───────────────────────────────────────────────────────────────────────┘
                              ↓ 评估/可观测
   RAGEvaluator(Recall@K/Precision@K/MRR/nDCG@K + Faithfulness + Hallucination)
   RetrievalTrace / StageTraceRecorder / RetrievalFailureAnalyzer
```

---

## 2. 高频面试题 & 参考回答

### Q1 · 你们 RAG 整体架构怎么设计的？
**答（骨架）**：分两层。**摄入层**用 `KnowledgeIngestionService` 编排「解析→分块→质检→嵌入→入库」，质检包含 PII 脱敏、chunk 质量评分、脏数据拦截复核、元数据绑定；**检索层**是 `RagSearchPipeline`（参考 Snail AI 的 Pipeline 模式），把所有检索/后处理封装成 `RagSearchHandler` 按 `getOrder()` 排序执行，支持提前终止和异常分级。**最大特点**是多路召回 + RRF 融合 + 重排，而不是单路向量检索；并且检索质量低于阈值会直接拒答而非硬编。

### Q2 · 为什么要多路召回？你有哪些路？
**答**：单路向量检索容易漏精确匹配（如商品编码、型号）和长尾关键词。我做了 5–7 路：
- 精确匹配 `ExactMatchHandler`、关键词 `KeywordSearchHandler`、`BM25` 全文 `Bm25SearchHandler`；
- 语义向量 `KnowledgeSearchHandler`（经验知识库）；
- 图检索 `GraphSearchHandler`（写死的商品关系图做推荐）+ `LightRagSearchHandler`（LLM 抽的实体-关系图，1 跳三元组扩展，答"A 和 B 什么关系"这类问题）；
- 查询扩展 `MultiQueryHandler`（一条变多条，提升召回）。
每路独立产出结果，互不阻塞（某路异常只标记降级），最后统一融合。

### Q3 · RRF 融合的原理？为什么不直接用向量相似度加权？
**答**：各路召回的分数尺度不统一（BM25 是 TF-IDF 量级、向量是余弦 0–1、图是命中数），直接加权要调参且脆弱。**RRF（Reciprocal Rank Fusion）只看排名不看重分数**：`score = Σ 1/(k + rank)`，`k=60`（见 `RrfFusionHandler.RRF_K=60`）。同一文档被多路排在前面就累加得分。优点是无须归一化、抗单路噪声、实现简单。融合后取候选池（默认 20，远大于最终注入条数）留给后续重排精排。质量分用 `topRrf / (pathCount/(60+1))` 归一化，低于 0.30 阈值判低质量。

### Q4 · 重排（Reranker）是必须的吗？用什么？
**答**：两阶段检索是业界定式——**Bi-Encoder 向量粗筛（高召回）+ Cross-Encoder 重排精排（高精度）**。`Reranker` 接口定义 `(query, doc) → score`，默认 `identity()` 恒等（上线初期不依赖重排模型也能跑）。我们接入 `BgeReranker`/`SafeReranker`（带降级保护）。`RerankHandler` 在融合后（Order=110）执行，并支持**自适应 Top-K**（文章 Q⑦）：不同查询最终截断条数 `resolver.apply(query)` 动态决定，而非固定 5。一个真实优化点：最初候选池硬编码 5，重排只能看到 ≤5 条根本没法提纯，改成候选池 20 + 自适应 K 后才真正生效。

### Q5 · 分块策略怎么选？为什么中文用字符估算 token？
**答**：用 `RecursiveChunkStrategy`（参考 LangChain `RecursiveCharacterTextSplitter`）：按分隔符优先级递归切——段落 `\n\n` > 行/句号/问号 > 逗号顿号 > 固定长度回退，并带 overlap 防割裂。中文用**字符级估算 token**（`estimateTokens`：汉字 1:1，英文 0.4x），因为 BGE-zh 是字符级 tokenizer，近似 1 中文字≈1 token，比调 tokenizer 更省。另外有 `ParentChildDocumentChunker` 做**父子双粒度**：检索用小块保证精度，生成时回链父块保证上下文完整。

### Q6 · 嵌入模型用什么？维度怎么定？踩过坑吗？
**答**：本地 `BgeEmbeddingModel`（ONNX `bge-large-zh-v1.5`），通过 `BgeEmbeddingConfig` + `EmbeddingClient` **条件切换**——配了 `embedding.service.url` 就走远程，否则本地加载，零代码改动。`RagVectorStoreConfig` 里 `PgVectorStore.dimensions(1024)`——**维度必须和嵌入模型输出强绑定**，切换模型要同步改维度，否则建表维度和实际向量不符会直接报错。我们曾因维度硬编码踩过坑（历史上 DashScope 1024 维 → 切本地 BGE 后的维度治理），现在维度跟着模型走。

### Q7 · 知识库怎么更新？增量还是全量？脏数据怎么防？
**答**：**增量 upsert，绝不每次全量 reindex**（这是早期架构缺陷 #3，已修：`reindexOnIngest=false`，embedding 在 `addDocument` 内完成）。配合 `ContentHashCache` + `HashUtil.normalizeAndHash` 做变更检测，页脚时间/广告位这类假变更不会触发重算。脏数据防线上：① `DocumentValidator` + `ReviewQueueService` 把脏数据 100% 拦进复核队列（REQ-1），而非信任摄入；② `DocumentMetadataEnricher` 绑定版本/时效/分类/ACL/sourceType；③ 版本治理用 `IngestionJob` + `KnowledgeIndexMetaService` 的 active 版本（替代硬编码 v1），支持审核/回滚。

### Q8 · 检索质量怎么量化？有评估体系吗？
**答**：有 `RAGEvaluator` 编排端到端评估，整合三类：
- 检索指标 `RetrievalMetrics`：Recall@K / Precision@K / MRR / nDCG@K；
- `ContextFaithfulnessChecker`：答案是否忠于上下文；
- `HallucinationDetector`：幻觉率。
同时全链路 `RetrievalTrace`/`StageTraceRecorder` 记录每个 Handler 耗时与 `PipelineError`（handler 名/错误码/时间戳），`RetrievalFailureAnalyzer` 做失败归因。生产上还有一个**实例**：质量分低于阈值直接 `RetrievalQualityResult.insufficientEvidence` 拒答，而不是拿低质上下文硬编。

### Q9 · 生产环境怎么保证稳定？降级策略？
**答**：三态存储 `app.rag.store.mode = pg | memory | auto`（默认 auto：启动试 PG，失败整体降级内存；运行时按请求降级）。`Degrade` 配置连续失败 3 次触发整体降级。`RagSearchPipeline` 捕获每个 Handler 异常，归一为 `PipelineError` 并标记 `degraded`，**绝不因为一个路径挂了拖垮整条链路**。`Reranker`/`ImageCaptioner`/`LightRagSearchHandler` 都带 Noop/降级实现，Bean 未注入或异常时静默跳过。合规有 `default-strategy = warn|rewrite|block`（默认 rewrite）做生成后校验。

### Q10 · 你们在 RAG 上踩过哪些坑 / 做过哪些优化？（压轴题，讲这条最加分）
**答（挑 3–4 条讲）**：
1. **候选池硬编码 5 → 重排失效**：改成候选池 20 + 自适应 Top-K，重排才真正提纯（见 `RrfFusionHandler` 注释）。
2. **每次摄入强制全量 reindex → 改成增量 upsert**，多实例互不触发重算（REQ-4）。
3. **脏数据信任摄入 → 改成 DocumentValidator + ReviewQueue 100% 拦截**。
4. **向量维度硬编码踩坑**：维度必须跟嵌入模型走，已改为条件切换 + 维度随模型。
5. **PDF 脏数据**（之前最弱项，已收口）：表格用列桶算法支持合并单元格、多栏按间隙分段、OCR 中文默认 `chi_sim+eng` 生效、扫描件空文本页自动触发 OCR、解析质量指标透出 + 降级告警（见 Q3 PDF 解析收口 commit `0448b04`）。

---

## 3. STAR 速记（讲不清就背这几条）

| 维度 | 一句话素材 |
|---|---|
| 架构 | Pipeline 模式 + 多路召回 + RRF 融合 + 重排，不是单路向量检索 |
| 融合 | RRF `1/(60+rank)` 跨路累加，抗尺度不一、抗噪声 |
| 分块 | 递归字符切分 + 父子双粒度 + 中文字符级 token 估算 |
| 嵌入 | 本地 BGE-ONNX，条件切换远程，维度跟模型走 |
| 摄入治理 | 增量 upsert + 内容哈希跳过重算 + 脏数据 100% 拦截复核 |
| 质量 | RAGEvaluator（Recall@K/MRR/nDCG + Faithfulness + 幻觉）+ 低分拒答 |
| 稳定 | 三态存储 auto 降级 + Handler 异常分级不中断 + Noop 降级实现 |
| 优化亮点 | 候选池 20 取代硬编码 5、增量取代全量 reindex、PDF 脏数据收口 |

---

## 4. 面试官可能追问的深水区（提前准备）

1. **RRF 的 k 怎么选？** 一般 60；k 越大越削弱高排名优势、越平滑。可答"经验值，配合候选池和重排使用，我们不单独调 k"。
2. **重排模型延迟怎么控？** Cross-Encoder 比 Bi-Encoder 慢，所以只在融合后的候选池（≤20）上跑，不在全量文档上跑。
3. **父子分块检索时怎么回链父块？** 检索命中 child，用 parent_id 取完整父块喂给 LLM，平衡精度与上下文。
4. **增量 upsert 怎么处理删除/更新？** 用内容哈希：hash 变了就 upsert，缺失的旧 hash 触发删除；配合 indexVersion 做整体切换/回滚。
5. **多租户/ACL 怎么隔离？** `MetadataEnricher` 打 ACL 标签，`AuthorityLevel` 做权限过滤，检索结果按调用方权限裁剪。
6. **图谱检索和向量检索结果冲突怎么办？** `CrossDocumentConflictResolver` 做跨文档冲突消解，融合阶段按路径可信度加权。

---

## 5. 避坑清单（面试别说错）

- ❌ 别说"我们直接用 LangChain 的 Retriever"——我们是**自研 Pipeline + Handler SPI**，可独立替换每阶段。
- ❌ 别说"向量库固定用 Milvus"——是**三态可切换**，默认 auto 降级内存。
- ❌ 别把 RRF 说成"加权平均"——它**只看排名不看分数**。
- ✅ 强调"质量拒答"和"脏数据拦截"——这是生产级 RAG 和玩具 RAG 的区别，最加分。
- ✅ 提到具体 commit / 类名 / 常量（如 `RRF_K=60`、`candidatePoolK=20`、`qualityThreshold=0.30`）能立刻建立可信度。
