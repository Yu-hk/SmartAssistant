# RAG 检索优化对照分析 —— JavaGuide《万字详解 RAG 优化》 vs SmartAssistant 现状

> 参考文章：https://javaguide.cn/ai/rag/rag-optimization.html
> 分析日期：2026-07-25
> 范围：检索链路（查询优化 → 召回 → 重排 → 上下文 → 评估）。文档解析与分块部分已在
> `docs/rag-chunking-strategy-analysis.md` 单独分析，本文不重复（P0/P1 修复已完成：
> commit `609dd04` / `e7fa8da` / `19c6c0b`）。

---

## 一、文章核心主张

文章把 RAG 优化定义为**证据加工流水线的系统工程**：解析 → 切分 → 索引 → 召回 → 重排 → 上下文 → 生成 → 评估，任何一环出问题都会传染到下游。5 个灵魂拷问：

1. 正确证据有没有被召回？
2. 正确证据有没有排在足够靠前？
3. 放进上下文的内容是否足够少、足够准？
4. 模型有没有严格基于证据回答？
5. 每次改动有没有通过固定样本集验证（回放）？

---

## 二、逐项对照：文章优化点 × 项目实现

### 2.1 Query Rewrite / 多查询 / HyDE —— ✅ 已实现（但 product 默认关闭）

| 文章策略 | 项目实现 | 位置 |
|---|---|---|
| 规范化改写 | `QueryRewriteHandler`（LLM 改写，Order=2，默认启用） | common/rag/pipeline |
| Multi-Query | `MultiQueryService.expand()`，variantCount 1~5（默认3） | common/rag |
| Step-back Query | `MultiQueryService.generateStepBackQuery()` | 同上 |
| HyDE | `MultiQueryService.generateHydeDoc()` | 同上 |
| Query Decomposition | ❌ 未实现 | — |
| Self-Query（条件抽取过滤） | ❌ 未实现 | — |

**符合文章告诫的点**：改写结果替换 `variants[0]` 但原始 query 仍参与召回（文章强调"必须保留原始问题"）。

**⚠️ 偏差**：`MultiQueryHandler` 在 product 模块的开关 `product.rag.multi-query.enabled` **默认 false**——最有价值的口语化改写能力在主检索链路实际没开。

### 2.2 Hybrid Search + RRF —— ✅ 实现质量高于文章基线

文章说"两路召回（向量+BM25）+ RRF 是生产默认项"。项目实际是 **6 路**：

```
MultiQuery(0) → ExactMatch(10) → Keyword(20) → Bm25SearchHandler(30)
             → Knowledge(40) → Graph(50) → RrfFusionHandler(100)
```

- `RrfFusionHandler`：RRF_K=**60**（与 Azure AI Search 等业界默认一致），candidate-pool-k=20，quality-threshold=0.30
- `Bm25Scorer`：HanLP 分词，TOP_K=5
- 精确匹配路覆盖文章说的"错误码/SKU/型号必须关键词命中"场景

**⚠️ 缺陷**：`AdaptiveWeightHandler` 按查询类型计算稠密/稀疏权重（0.3~0.8），但 `RrfFusionHandler` 用**等权 RRF**——自适应权重计算了却没接入融合，是"半成品"。

### 2.3 Rerank —— ⚠️ 有链路、无真模型（最大短板）

文章：向量检索是双塔（"语义接近吗"），Rerank 要用 Cross-Encoder（"能不能回答这个问题"）。

项目现状：
- `RerankHandler`（Order=110）存在，但默认 scorer 是 `identity()=0.5`（**不排序**）
- product 注入 `EmbeddingScorer` 做二次精排——但这仍是 **bi-encoder 余弦相似度**，和粗召回同一套判别逻辑，无法解决"语义接近但答非所问"的问题
- `BgeReranker` 名字带 Reranker，实际是 titleSim×0.4 + contentSim×0.6 的余弦加权，**不是 bge-reranker 模型**（命名有误导性）
- `AdaptiveRerankTopK` 按查询类型自适应 top-k（开放式8/事实式3）——这个设计好，符合文章"Top-K 分层管理"

**结论：Rerank 环节形似而神不似。** 文章的例子（线程池拒绝策略：参数说明 vs 触发条件）在当前实现下无法被正确排序，因为两者与 query 的余弦相似度接近。

### 2.4 Top-K 分层 —— ✅ 部分符合

文章建议：recall_top_k(30~100) → rerank_top_n(5~10) → context_top_n(3~6)。

项目：candidate-pool-k=20（粗召回池） → rerank top-k 3~8（自适应） → 全部进上下文。
- 粗召回池 20 偏小（文章建议 30~100），且**缺第三层**：rerank 后没有独立的 context_top_n 裁剪。

### 2.5 Parent-Child 检索侧 —— ❌ 只做了一半（关键缺口）

文章："小块负责召回，大块负责生成"——检索命中子块后应取**父块内容**进上下文。

项目现状：
- 分块侧 ✅：`ParentChildDocumentChunker` 子256/父1024，子块写 `parentDocId`（且 P1 后子块带章节标题前缀，召回精度更好）
- 检索侧 ❌：grep 全库确认 `getParentDocId()` 仅出现在入库/状态更新链路（`PgVectorKnowledgeBase`/`KnowledgeIngestionService`），**没有任何"命中子块 → fetch 父块替换上下文"的代码**

**后果**：进入 LLM 上下文的是 256 token 的子块——正是文章说的"断章取义"风险。前期辛苦做的父块 1024 粒度**从未被消费**，Parent-Child 目前只是入库时的数据结构装饰。

### 2.6 上下文工程 —— ⚠️ 有去重、无压缩/排序策略

| 文章要求 | 项目现状 |
|---|---|
| 去重 | ✅ `DedupHandler`（Order=105）：SHA-256 精确 + 3-gram Jaccard>0.85 模糊 |
| 上下文压缩（围绕 query 过滤证据） | ❌ 无（只有 top-k 截断） |
| 排序策略（最相关在前/同文档保原序/标版本） | ❌ 按融合分数顺序直接拼接 |
| 冲突消解 | ✅ `CrossDocumentConflictResolver`（超出文章基线） |
| Prompt 证据边界（不足则拒答/带引用） | ⚠️ 需逐模块核查 prompt 模板，未确认 |

### 2.7 Metadata 预过滤 —— ⚠️ 字段齐、过滤时机未验证

`KnowledgeDocument` 有 tenantId/version/sourceType/authorityLevel/documentStatus 等字段（对齐文章的 metadata 清单）。文章的高危盲区——"先向量检索再权限过滤会导致召回数虚减/越权"——项目中 pgvector 查询是否把 tenant/status 作为 **SQL WHERE 预过滤**（而非检索后过滤）需要专项核查 `PgVectorKnowledgeBase` 的查询 SQL。

### 2.8 语义缓存 —— ✅ 文章未覆盖的加分项

`SemanticCacheService`：阈值 0.85、top-k=3、max-age 24h、按用户组隔离。这是文章没讲但对延迟/成本有实效的优化。

### 2.9 评估闭环 —— ⚠️ 指标齐全、检索侧回放缺失

- ✅ `RetrievalMetrics`：Recall@K / Precision@K / MRR / nDCG@K 计算齐全
- ✅ `RAGEvaluator`：+ Faithfulness + 幻觉检测；`EvalBaseline` 基线回归 + CI 门禁（eval-gate.yml）
- ❌ **没有检索专用 golden set**（question → golden_context 对），无法做文章强调的"改 Chunk/改召回 → 回放 → 比较 Context Recall"闭环。现有 golden suite 偏合规/端到端，检索环节的改动（比如本次 P1 标题注入）只能靠人工看日志验证。

---

## 三、能力矩阵总览

| 环节 | 文章要求 | 项目状态 | 评价 |
|---|---|---|---|
| Query Rewrite/MultiQuery/HyDE/StepBack | 生产建议 | ✅（product 默认关） | 实现全，开关没开 |
| Hybrid Search + RRF | 生产默认项 | ✅ 6路+RRF(K=60) | 超出基线 |
| 自适应融合权重 | — | ⚠️ 算了没用上 | 半成品 |
| Cross-Encoder Rerank | 生产建议 | ❌ 仅 bi-encoder | **最大短板** |
| Top-K 三层管理 | 生产建议 | ⚠️ 两层 | 池偏小、缺 context_top_n |
| Parent-Child 检索侧取父块 | 实用折中 | ❌ 未实现 | **关键缺口** |
| 上下文去重 | 必做 | ✅ Jaccard 0.85 | 达标 |
| 上下文压缩/排序 | 建议 | ❌ | 缺失 |
| Metadata 预过滤 | 高危盲区 | ⚠️ 待核查 SQL | 需专项确认 |
| 检索指标计算 | 必做 | ✅ recall/MRR/nDCG | 达标 |
| 检索 golden set 回放 | 必做 | ❌ | 闭环断点 |
| 语义缓存 | 未提及 | ✅ 0.85/24h | 加分项 |

---

## 四、改进建议（按投入产出比排序）

1. **P0 - 父子检索侧取父块**（约1天）：检索命中子块后按 `parentDocId` 批量 fetch 父块、去重后替换上下文。数据结构全部就绪，只差检索侧 20~40 行代码；直接消费已有的父块投资，是全链路收益最确定的一项。
2. **P0 - 打开 product 的 MultiQuery 开关**（0.1天）：`product.rag.multi-query.enabled=true` + 观察延迟。已实现的能力不启用等于没有。
3. **P1 - 检索 golden set + 回放**（1~2天）：50 条问题（含精确匹配/口语化/多跳/应拒答），存 question→golden_context，接入现有 `RetrievalMetrics` 与 EvalGate。没有这个，后续 rerank/权重调优都是"玄学"（文章原话）。
4. **P1 - 真 rerank 模型**（2~3天）：bge-reranker-base ONNX 本地推理（项目已有 BGE ONNX 基建 `BgeEmbeddingConfig` 可复用加载模式），替换 `EmbeddingScorer`；同时把 `BgeReranker` 改名（如 `CosineReranker`）消除误导。
5. **P2 - AdaptiveWeight 接入 RRF**（0.5天）：加权 RRF：`score = w_dense/(K+rank_dense) + w_sparse/(K+rank_sparse)`。
6. **P2 - Metadata 预过滤核查**（0.5天）：确认 `PgVectorKnowledgeBase` 查询 SQL 的 tenant/status 过滤在向量计算之前。
7. **P2 - 上下文排序与 context_top_n**（1天）：同文档片段保持原序、最相关在前、rerank 后再裁一层 3~6 条。

## 五、一句话结论

项目在**召回宽度**（6路+RRF）与**工程护栏**（去重/冲突消解/语义缓存/指标计算）上已超出文章基线，但文章排查路径的第 3 步"正确证据在候选池却没进上下文"恰是本项目最薄弱处——**rerank 无真模型、父块从未被消费、无检索回放集**。三者补齐后，链路才形成"召回全 → 排得准 → 上下文净 → 可回归"的闭环。
