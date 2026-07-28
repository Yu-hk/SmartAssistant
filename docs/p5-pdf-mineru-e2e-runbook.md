# P5 PDF 解析升级 · 端到端实测验证 Runbook

> 适用范围：验证 SmartAssistant RAG 的 MinerU PDF 增强解析链路在**真实 magic-pdf 引擎**下的端到端正确性，
> 覆盖 P5-A（Python sidecar 加固）、P5-B（图片向量化 SPI + 安全降级）、P5-C（端到端实测）。
> 沙箱/CI 无 magic-pdf，默认不执行真实链路；本 runbook 用于**已部署 magic-pdf 的用户本机**验证。

---

## 0. 验证目标（验收清单）

| 项 | 验收标准 | 对应子任务 |
|----|----------|-----------|
| A. Sidecar 健壮性 | sidecar 进程稳定、错误分类清晰（MINERU_NOT_INSTALLED/TIMEOUT/NO_OUTPUT/PARSE_ERROR/BAD_JSON） | P5-A |
| B. 图片向量化贯通 | 开启 `enabled-image-vectorization` + 可用视觉模型时，图片块标记 `pdf.imageVector=1` 并透传字节；模型不可用时安全降级（仅索引 caption/OCR 文本，不抛异常、不阻断解析） | P5-B |
| C. 端到端链路 | 真实 magic-pdf → sidecar → parser → `ParsedDocument` 全链路无异常，正确产出 text/table/image 类块 | P5-C |

---

## 1. 环境前置（仅真实引擎端到端需要）

1. **Python 3.10–3.12**（MinerU 的 `ray` 依赖不支持 3.13）。
   ```bash
   uv python install 3.12
   ```
2. **安装 magic-pdf（含权重）**：
   ```bash
   python -m venv .venv && source .venv/bin/activate
   pip install magic-pdf[full]
   # 模型默认从 HuggingFace 拉取；国内网络可设：
   export MINERU_MODEL_SOURCE=modelscope
   magic-pdf --version   # 确认安装成功
   ```
3. **（可选）OCR 引擎**：扫描件需 Tesseract 或 Ollama；数字 PDF 可省。
4. **视觉嵌入模型（仅 P5-B 启用图片向量化时需要）**：CLIP / BGE-Vision / 多模态 Embedding。
   未提供时 `NoopImageEmbeddingModel` 默认不可用，parser 自动降级（仅索引文本），**不影响解析**。

---

## 2. 自动化验证（推荐）

### 2.1 单元测试（无需 magic-pdf，沙箱/CI 可跑）
```bash
export JAVA_HOME="D:/Program Files/Java/jdk-21.0.6+7"   # 或本地 JDK 21
mvn -o -pl smart-assistant-common test \
  -Dtest='MinerUImageVectorizationTest,MinerUDocumentParserMappingTest,MinerUDocumentParserTest,MinerURoutingAndMappingTest,DocumentParseRouterMinerUTest,MinerUSidecarClientIntegrationTest'
```
- `MinerUImageVectorizationTest` 覆盖 P5-B 三态：未开启 / 模型不可用降级 / 模型可用向量化贯通（4 用例全绿）。
- `MinerUSidecarClientIntegrationTest` 用 stub sidecar 验证 stdio 协议与进程池（不依赖真实 magic-pdf）。

### 2.2 真实引擎端到端（需 magic-pdf，默认关闭）
```bash
mvn -o -pl smart-assistant-common test -Dp5.e2e.enabled=true \
  -Dtest='MinerURealEngineE2ETest'
```
- 仅当 `-Dp5.e2e.enabled=true` **且** 环境可 `import magic_pdf` 时执行真实链路；
  否则自动 `skip`，**绝不阻塞普通构建**。
- 链路：PDFBox 生成含内嵌图片的 PDF → `MinerUSidecarClient`（真实 magic-pdf）→ `MinerUDocumentParser`（开启图片向量化 + 假可用模型）→ 断言解析成功且图片块被向量化（`pdf.imageVector=1`）。

### 2.3 Python sidecar 自测（P5-A）
```bash
python smart-assistant-common/src/test/python/test_mineru_sidecar.py
# 期望：10 passed, 0 failed
```

---

## 3. 手动验证（业务接入视角）

### 3.1 配置（`application.yml`）
```yaml
app:
  rag:
    mineru:
      enabled: true                 # 总开关
      route-on-images: true         # 含图片即路由 MinerU（OCR+版面+表格）
      enabled-image-vectorization: false   # P5-B：默认关；接视觉模型后改 true
      images-temp-dir: /tmp/mineru
```
> 仅 `enabled=true` 时 `DocumentParseRouter` 才切换为 `PdfParserRouter`（PDFBox 主 / MinerU 补）；
> 关闭时行为完全不变（纯 PDFBox，零回归）。

### 3.2 启动与摄取
1. 启动业务模块，确保 sidecar 进程池预热日志出现：
   `[MinerU] sidecar 常驻进程池预热完成: instances=1`
2. 调用知识库摄取接口，提交一份**含图片/表格的 PDF**（如产品手册）。

### 3.3 验证点（日志 + KB）
- **链路命中**：日志出现 `[MinerU] 解析完成: file=xxx.pdf, elements=N`。
- **块类型正确**（R4 映射）：
  - 正文 → `contentType=pdf`
  - 表格 → `contentType=pdf-table`（表格 Markdown 入索引）
  - 图片+caption（同页无正文）→ `pdf-image-caption`
  - 图片+整页 OCR → `pdf-ocr`
  - 图片+内嵌 OCR（同页有正文）→ `pdf-image-ocr`
  - 元数据标记 `pdf.caption=1` / `pdf.ocr=1`
- **图片向量化（开启后）**：日志出现 `[MinerU] 图片向量化成功: file=..., dim=N`；
  对应文档元数据含 `pdf.imageVector=1`、`pdf.imageVectorDim=N`，且 `ParsedDocument.getImageBytes()` 携带图片字节（@JsonIgnore，不进常规传输）。
- **安全降级（模型不可用时）**：无任何 `pdf.imageVector` 标记，但仍正常产出 caption/OCR 文本块，且日志无异常。

---

## 4. 接入真实视觉嵌入模型（P5-B 生产启用）

`ImageEmbeddingModel` 为 SPI 接口，默认 `NoopImageEmbeddingModel`（不可用→安全降级）。
接入真实模型只需提供一个同名 `@Primary` Bean，parser 侧零改动：

```java
@Configuration
public class VisualEmbeddingConfig {
    @Bean
    @Primary
    public ImageEmbeddingModel imageEmbeddingModel() {
        return new ClipImageEmbeddingModel(/* 权重/端点 */);
    }
}
```
启用：将 `app.rag.mineru.enabled-image-vectorization` 改为 `true`。

---

## 5. 已知边界与降级

- **无 magic-pdf / sidecar 启动失败**：`MinerUSidecarClient` 预热抛 `DocumentParseException`，
  `PdfParserRouter` 按 `fallback-to-pdfbox=true` 回退 PDFBox 全链路（含其图片 caption/OCR 兜底）。
- **图片向量化失败**（读图异常 / 嵌入异常 / 模型不可用）：`MinerUDocumentParser.tryVectorizeImage`
  捕获一切异常返回 `null`，仅索引文本，解析不中断。
- **Windows 路径**：sidecar 的 `image_path` 仅存 basename，Java 端按 `images_dir + basename` 拼装，
  规避绝对路径在 `Paths.get` 上的平台差异。

---

## 6. 提交与测试证据

- P5-A：`mineru_sidecar.py`（错误分类 + 多候选 content_list/path 解析）+ `test_mineru_sidecar.py`（10/10）。
- P5-B：`ImageEmbeddingModel` / `NoopImageEmbeddingModel` SPI、`ParsedDocument.imageBytes` 载体、
  `MinerUDocumentParser.tryVectorizeImage` 安全降级、`MinerUAutoConfiguration`/`IngestionJobAutoConfiguration` Bean 接线、
  `MinerUImageVectorizationTest`（4 用例）。
- P5-C：`MinerURealEngineE2ETest`（opt-in + 自动 skip）、本 runbook。
