# RAG 切片策略分析 —— 对照 JavaGuide《RAG 文档处理》与 SmartAssistant 现状

> 分析对象：
> 1. 文章 https://javaguide.cn/ai/rag/rag-document-processing.html 提出的切片策略体系
> 2. 本仓库真实代码：`PdfDocumentParser` / `KnowledgeIngestionService` / `ParentChildDocumentChunker` / `SemanticChunkStrategy` / `RecursiveChunkStrategy`
> 3. 之前 PDF 切分验证（`docs/pdf-chunking-validation-report.md`）暴露的质量缺陷

---

## 一、文章切片策略全景

| 策略 | 原理 | 适用场景 | 文章推荐参数 |
|------|------|----------|--------------|
| **固定长度 Fixed-size** | 按字符/Token 硬切，相邻块重叠 | 短文档、FAQ、基线 | 1000 Token + 200 重叠（示例） |
| **递归字符 Recursive** | 段落→句子→词逐级切，模拟人读书 | 结构不规则（博客/手册/报告） | 通用 400–512 Token；代码 ~100+15 |
| **语义 Semantic** | embedding 判断句间相似度聚类 | 语义连贯但无显式边界 | min_chunk 200–400 Token（防超小碎片） |
| **结构感知 Structure-aware** | 按标题层级/页/标签切 | Markdown/HTML/PDF/代码/论文 | MD 按 H1-H3；PDF 按页或章节 |
| **父子 Parent-Child** | 小块(检索)挂大块(阅读) | 长文档/教程/政策/故障手册 | 子~300 Token，父~1200 Token |
| **重叠 Overlap** | 块边界补上下文 | 全部 | 512 Token 块 + 50–100 重叠 |

**文章核心结论（直接引用）：**
- "RAG 上限由数据质量决定，下限由检索策略决定；瓶颈在文档进入索引前的管线。"
- "切分破坏上下文依赖，Embedding 仅看局部窗口。" → 应对：摘要/问题变体入口、保留层级 Metadata、**Late Chunking**、**Contextual Chunking**。
- "把数据处理管线做到位，比换一百个 embedding 模型都管用。"

---

## 二、本项目当前切片管线（真实实现）

**链路：**
```
PDF → PdfDocumentParser（PDFBox）→ ParsedDocument 列表
     → KnowledgeIngestionService（分流 + 质检 + 入库）
        → ParentChildDocumentChunker（双粒度编排）
           → SemanticChunkStrategy（主策略）
              → RecursiveChunkStrategy（超长段 fallback）
```

**关键代码事实（带位置）：**

1. `PdfDocumentParser.java:106` —— 正文"**排除表格文本块后，按空行分割为段落**"，**每个段落**生成一个独立 `ParsedDocument`（contentType=`pdf`）。
2. `PdfDocumentParser.java:88-101` —— 表格经 x/y 坐标聚类重构为 Markdown，作为 **`pdf-table` 独立文档**输出。
3. `KnowledgeIngestionService.java:376-399` —— 分流：`pdf-table`/`html`/`word` 视为**结构化**，整篇直接入库、**完全跳过 chunking**；其余 `textDocs` 走分块器。
4. `ParentChildDocumentChunker.java:55-57` —— 默认构造：`new SemanticChunkStrategy(), 256, 1024, 50`（子 256 / 父 1024 / 重叠 50）。
5. `ParentChildDocumentChunker.java:82-121` —— **逐元素独立分块**：`chunkStrategy.chunk(element.getContent(), ...)`，每个 `ParsedDocument` 单独切父块、再切子块。**跨元素（跨段落）不累积**。
6. `SemanticChunkStrategy.java:42-49` —— 语义边界靠正则识别（`#` 标题、`第X章`、`X.` 编号、`一、` 等），**无前缀注入**（`prefix` 恒为 `""`）。

---

## 三、逐项对照（文章 vs 本项目）

| 文章策略 | 本项目是否采用 | 符合度 | 差距 |
|----------|----------------|--------|------|
| 父子 Parent-Child | ✅ 采用（256/1024） | ⚠️ 部分 | 参数方向对，但**逐元素分块使父块 1024 不可达**（见第四节 #1） |
| 递归 Recursive | ✅ 作为 fallback | ✅ | 分隔符优先级合理；中文 1 字≈1 token 估算务实 |
| 语义 Semantic | ✅ 作为主策略 | ⚠️ 部分 | 用正则边界近似"语义"，非 embedding 聚类，避免了超小碎片与额外成本（优点）；但中文编号 `X. ` 易误切列表项 |
| 结构感知 | ⚠️ 仅标题正则 | ❌ 弱 | PDF 被**提前碎片化**为段落级元素，结构边界在解析层就丢失，分块层拿不到"长文档" |
| 固定长度兜底 | ✅ `RecursiveChunkStrategy.splitByFixedLength` | ✅ | 分隔符耗尽后硬截断兜底，符合文章"降级"思路 |
| 重叠 Overlap | ✅ overlap=50 | ✅ | 文章建议 50–100，取值合理 |
| Late/Contextual Chunking | ❌ 未采用 | ❌ | 文章明确列为语义丢失应对手段，本项目缺失 |
| 质量闭环采样校验 | ⚠️ 仅 PiiScrubber + ChunkQualityScorer | ⚠️ | 文章主张三道关卡（格式/解析/Chunking 校验），本项目缺 Chunking 层统计校验 |

---

## 四、核心问题诊断（结合代码 + PDF 验证）

### #1 父块粒度退化（1024 Token 形同虚设）— 最关键
- **现象**：PDF 验证显示父块实际 156–188 Token，与配置 1024 相去甚远。
- **根因**：`PdfDocumentParser` 把正文切成**段落级独立元素**，而 `chunkParentChild` 对每个元素**独立**调用 `chunkStrategy.chunk(element.getContent(), 1024, 50)`。单段落仅 ~150 Token，远小于 1024，于是父块 = 整段，**跨段落上下文从未累积**。
- **与文章背离**：文章的 Parent-Child 前提是"先有长文档，再切出 300 Token 子块挂到 1200 Token 父块"。本项目在解析层就把长文档打散，父子策略的前提被破坏。

### #2 Parent-Child 双粒度趋同（1:1 退化）
- 由 #1 直接派生：父块与子块都是同一段 ~150 Token 文本 → 父==子，**双粒度退化为单粒度**，检索命中父块与子块提供的信息完全一致，失去"小块召回 + 大块补上下文"的设计收益。

### #3 章节标题未注入子块前缀
- `SemanticChunkStrategy.mergeSections` 产出的 `Chunk` 始终 `prefix=""`（第 154-155、168-169 行）。
- 文章强调"保留层级 Metadata / 语义入口"。当前章节标题虽留在段落文本首行，但没有作为**独立上下文前缀**注入子块，跨块检索时标题语义易丢失。

### #4 表格检测器误报 → 正文绕过 chunking 与质检
- PDF 验证：单栏中文 PDF 每文档产出 5 个 `pdf-table`，其中仅 1 个为真表，其余是含 `|` 的普通段落被误判。
- 后果：`KnowledgeIngestionService` 将误报的 `pdf-table` 整篇入库，**跳过分块、跳过 PII/质量门禁**（第 381-398 行只做结构化直通）。误报正文既未被切分，也未被质检，污染检索。

### #5 语义边界正则误切
- `NUM_SECTION`（`^(\d+\.?)(\d+\.?)?\s+`）会把"**1. 引言**"这类列表项切在句首，但若正文出现"价格 1. 优惠"等数字开头行也会误判为章节边界，造成不该断的地方断。

---

## 五、与文章推荐方案的差距小结

| 文章主张 | 本项目现状 | 缺口等级 |
|----------|------------|----------|
| 长文档用 Parent-Child，先保证"长输入" | 解析层过度碎片化，父子策略前提被破坏 | P0 |
| 结构清晰按结构切（PDF 按页/章节） | 解析层已丢结构，分块层无结构感知 | P0 |
| 表格密集文档表格单独成块、禁跨块 | 表格检测器误报，正文被整篇入库 | P0 |
| 保留层级 Metadata / 标题作为上下文 | 标题未注入子块 prefix | P1 |
| Late / Contextual Chunking 补语义 | 未实现 | P1 |
| Chunking 层质量校验（大小标准差/最小最大） | 仅兜底式质检 | P2 |

---

## 六、改进建议（按优先级）

**P0 — 修解析/合并层，恢复"长文档"输入**
- 在 `PdfDocumentParser` 输出后、`chunkParentChild` 之前，增加**按标题层级/页**的段落合并步骤：把同一章节的连续段落拼成"长元素"再喂给分块器。这样父块 1024 才能跨段落累积，Parent-Child 双粒度才能真正成立。

**P0 — 修表格检测器**
- `detectTables()` 增加约束：**≥2 列** + **表头特征**（首行与后续行对齐、含分隔符行 `|---|`）。单栏中文文档中 `|` 普通文本不应被判为表。避免误报正文绕过 chunking 与质检。

**P1 — 章节标题注入子块前缀**
- `SemanticChunkStrategy` 切分时记录当前所属标题，写入 `Chunk.prefix`；`ParentChildDocumentChunker` 拼 `prefix + text` 时子块即携带标题上下文（当前代码已支持 `getPrefix()`，只是始终为空）。

**P1 — 引入 Contextual / Late Chunking**
- 入库前用 LLM 或规则为每个子块生成一句话摘要/问题变体，作为额外检索入口（对齐文章"增语义入口"）。

**P2 — Chunking 层统计校验**
- 在 `KnowledgeIngestionService` 分块后统计块大小标准差、最小/最大 Token，超阈（<50 或 >5000）预警或降级为固定长度兜底，落实文章三道关卡。

**P2 — 纳入 CI 回归**
- 已有 `PdfChunkingValidationTest`，建议追加断言：父块 Token 应明显大于子块（验证双粒度不趋同）、`pdf-table` 数量应等于真表数（验证检测器）、子块应含标题前缀。

---

## 七、结论（一句话）

> 策略**选型**完全符合文章方向（Parent-Child + 语义 + 递归兜底 + 重叠），但**实现层**的"逐元素独立分块"让 `parentMaxTokens=1024` 形同虚设、双粒度退化为 1:1——这是与文章最佳实践最大的背离点。**改解析/合并层（先拼长文档再切父子）+ 修表格误报**，即可把设计意图真正落地，无需推翻现有架构。
