# PDF 文档切分验证报告

> 验证目标：用项目**当前分支（hardening/quality-p0-p1-p2）真实的解析与切分代码**，对一批与项目方向相关的中文知识库 PDF（下单 / 退单 / 支付）进行端到端切分，评估实际效果是否达到设计预期。

生成日期：2026-07-24

---

## 一、生成的样例 PDF（与项目方向相关）

| 文件 | 主题 | 页数 | 结构 |
|------|------|------|------|
| `docs/sample-pdfs/下单操作指南.pdf` | 下单流程 / 支付方式 / 订单确认 / FAQ | 2 | 5 节 + 1 张支付限额表 + 编号列表 |
| `docs/sample-pdfs/订单取消与退款政策.pdf` | 取消条件 / 退款时效 / 不予退款情形 / 流程 | 2 | 6 节 + 1 张退款时效表 + 编号列表 |
| `docs/sample-pdfs/支付安全与发票说明.pdf` | 支付安全 / 渠道 / 发票类型 / 重开 | 1 | 5 节 + 1 张渠道表 |

全部使用 reportlab + 内置中文 CID 字体（STSong-Light）生成，结构含标题层级（`一、二、三`）、Markdown 表格、长短段落，用于触发不同切分粒度。

---

## 二、验证方法（对齐生产管线）

调用的就是生产代码，未做简化：

1. **解析**：`com.example.smartassistant.common.rag.document.PdfDocumentParser`
   （PDFBox 3.x，双栏检测 + 表格感知，按页产出 `ParsedDocument`）。
2. **切分**：`com.example.smartassistant.common.rag.chunking.ParentChildDocumentChunker`
   默认构造参数 = `childMaxTokens=256 / parentMaxTokens=1024 / overlap=50`，内部用 `SemanticChunkStrategy`。
3. **测试**：`smart-assistant-common/.../rag/ingestion/PdfChunkingValidationTest.java`
   （3 个 PDF 全部通过断言：子块关联父块、无超阈值子块、子块粒度 ≤ 父块）。

---

## 三、切分结果总表

| 指标 | 下单操作指南 | 订单取消与退款 | 支付安全与发票 |
|------|------------|--------------|--------------|
| 解析元素数 | 7 | 6 | 6 |
| 类型分布 | pdf=2, **pdf-table=5** | pdf=1, **pdf-table=5** | pdf=1, **pdf-table=5** |
| 正文总字符 | 463 | 295 | 230 |
| **父块数（≤1024tok）** | 7 | 6 | 6 |
| **子块数（≤256tok）** | 8 | 7 | 6 |
| 父块 token（min/avg/max） | 143 / **188** / 277 | 109 / **163** / 272 | 137 / **156** / 216 |
| 子块 token（min/avg/max） | 110 / 164 / 237 | 109 / 139 / 161 | 137 / **156** / 216 |
| 含章节标记的父块 | 2/7 | 4/6 | 1/6 |
| 超阈值(>307tok)子块 | 0 | 0 | 0 |

---

## 四、关键发现

### 发现 1：父块粒度严重退化，`parentMaxTokens=1024` 从未生效

设计意图是「检索命中小块（子块）→ 回链到大块（父块，~1024 token）给 LLM 阅读」。
但实际：**每个解析元素独立切分，而单元素均 < 280 token，远小于 1024 上限**，因此每个元素恰好生成 1 个父块。
父块平均仅 **156–188 token**，与「大上下文父块」的设计相去甚远——`parentMaxTokens` 形同虚设。

> 根因在解析层：`PdfDocumentParser` 已把文档预先拆成「段落级」`ParsedDocument`（再叠加下面的表格误报），下游 chunker 拿到的已是碎片，无法再聚合成大父块。

### 发现 2：表格检测器对单栏中文 PDF 严重误报

每份文档只作者了 **1 张真实表格**，但解析器产出 **5 个 `pdf-table` 元素**。逐元素核对：

- `[1] pdf-table` → 真实的支付/退款限额表（正确）
- `[0][3][5] pdf-table` → 普通段落被从**句子中间**用 `|` 切开成 2 列，混入 `|---|---|` 分隔符（误报）
- `[2] pdf-table` → 编号列表项 `6 | 在支付页完成付款…`（把「序号 | 文本」误判为 2 列表格）

后果：真实正文元素仅剩 **1–2 个（pdf）**，文档被大量伪表格碎片瓜分，且正文内容被 `|` 污染。

### 发现 3：Parent-Child 双粒度趋同，设计收益丧失

由于父块本身已很小，子块（≤256）几乎不再细分：

- 下单：父 188 / 子 164
- 退款：父 163 / 子 139
- 支付：**父 156 / 子 156（完全相等）**

「小粒度检索 + 大粒度阅读」的双层结构退化为近似单层，回链父块对 LLM 几乎没有额外上下文增益。

### 发现 4：章节标题覆盖率低

仅 **1–4 / 6–7** 个父块包含「一、/二、」等章节标记。意味着：检索命中的子块，其回链父块常常**不带章节标题**，丢失了最重要的归属与语义线索，RAG 回答容易失去范围感。

---

## 五、结论与建议

**结论**：当前管线在**结构清晰、单栏排版**的中文知识库 PDF 上，切分结果偏离 Parent-Child 设计预期——父块粒度退化到段落级、表格检测器误报把正文切碎成伪表格、双粒度收益丧失、章节上下文大量丢失。功能性断言（关联/不超阈值）虽通过，但**质量维度不达标**。

**建议（按投入产出比）**：

1. **P0 收紧表格检测器**：要求「≥2 列 **且** 存在表头行特征（如首行后紧跟 `|---|` 或重复分隔符）」，并提高最小行数/列对齐容忍阈值。可先消除 4/5 的伪表格。
2. **P1 解析后合并再切分**：在 `KnowledgeIngestionService` 切分前，将同页/同节的 `pdf` 正文元素按标题层级合并为较大文本，再交给 `ParentChildDocumentChunker`，使 `parentMaxTokens=1024` 真正生效。
3. **P1 保留标题上下文**：切分时把最近章节标题注入每个子块的关键词/前缀，弥补「回链父块无标题」问题。
4. **P2 评测固化**：将本报告中的 `PdfChunkingValidationTest` 纳入 CI，并以「父块平均 token ≥ 600」「伪表格占比 = 0」「章节标记覆盖率 ≥ 80%」作为回归门禁。

---

## 六、复现方式

```bash
# 1) 生成样例 PDF（已生成于 docs/sample-pdfs/）
python scripts/gen_sample_pdfs.py

# 2) 运行验证（JDK 21，离线 Maven）
./mvnrun.sh test -pl smart-assistant-common \
  -Dtest=PdfChunkingValidationTest -Dsurefire.failIfNoSpecifiedTests=false
```

产物：
- 样例 PDF：`docs/sample-pdfs/*.pdf`
- 验证测试：`smart-assistant-common/src/test/java/com/example/smartassistant/common/rag/ingestion/PdfChunkingValidationTest.java`
- 测试资源（PDF 副本）：`smart-assistant-common/src/test/resources/knowledge-pdfs/*.pdf`
