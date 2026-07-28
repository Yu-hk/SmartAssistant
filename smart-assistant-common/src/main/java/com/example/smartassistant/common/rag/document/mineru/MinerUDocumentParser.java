/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document.mineru;

import com.example.smartassistant.common.rag.document.DocumentParseException;
import com.example.smartassistant.common.rag.document.DocumentParser;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MinerU 文档解析器——实现 {@link DocumentParser}，复用既有契约。
 * <p>
 * 持有 {@link MinerUClient}（v1 为 {@link MinerUSidecarClient}）；{@code parse()} 调 client 得到
 * 结构化 JSON，再映射为 {@code List<ParsedDocument>}，下游 {@code DocumentChunker} /
 * {@code DocumentMetadataEnricher} 零改动（仅依赖 ParsedDocument 字段与 contentType）。
 * </p>
 *
 * <p><b>contentType 映射（R4）</b>：</p>
 * <ul>
 *   <li>text → {@code pdf}</li>
 *   <li>table → {@code pdf-table}</li>
 *   <li>image + caption + 无同页文本 → {@code pdf-image-caption}</li>
 *   <li>image + 整页 ocr → {@code pdf-ocr}</li>
 *   <li>image + 内嵌 ocr → {@code pdf-image-ocr}</li>
 *   <li>caption 优先于同图 OCR（R5 独占）</li>
 * </ul>
 */
public class MinerUDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(MinerUDocumentParser.class);

    private final MinerUClient client;
    private final MinerUProperties properties;
    /** 图像嵌入模型（可空：空则图片不向量化，仅索引 caption/OCR 文本）。 */
    private final ImageEmbeddingModel imageEmbeddingModel;

    public MinerUDocumentParser(MinerUClient client) {
        this(client, null, null);
    }

    public MinerUDocumentParser(MinerUClient client, MinerUProperties properties) {
        this(client, properties, null);
    }

    public MinerUDocumentParser(MinerUClient client, MinerUProperties properties,
                                ImageEmbeddingModel imageEmbeddingModel) {
        this.client = client;
        this.properties = properties;
        this.imageEmbeddingModel = imageEmbeddingModel;
    }

    @Override
    public List<ParsedDocument> parse(String filePath) throws DocumentParseException {
        Path path = Paths.get(filePath);
        String fileName = path.getFileName().toString();
        String sourceUrl = path.toAbsolutePath().toString();
        String requestId = UUID.randomUUID().toString();

        String imagesDir = resolveImagesDir(requestId);
        MinerUParseRequest req = new MinerUParseRequest(
                path.toAbsolutePath().toString(), "all", requestId, imagesDir);

        MinerUParseResponse resp = client.parse(req);
        List<ParsedDocument> docs = mapResponse(fileName, sourceUrl, resp, imagesDir);
        log.info("[MinerU] 解析完成: file={}, elements={}", fileName, docs.size());
        return docs;
    }

    /** 解析图片临时目录（按 requestId 隔离），创建失败仅告警，不影响主流程 */
    private String resolveImagesDir(String requestId) {
        String base = (properties != null && properties.getImagesTempDir() != null)
                ? properties.getImagesTempDir()
                : System.getProperty("java.io.tmpdir") + "/mineru";
        Path dir = Paths.get(base, requestId);
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (Exception e) {
            log.debug("[MinerU] 创建图片临时目录失败: {}", e.getMessage());
        }
        return dir.toAbsolutePath().toString();
    }

    // ==================== JSON → ParsedDocument 映射（R4） ====================

    private List<ParsedDocument> mapResponse(String fileName, String sourceUrl,
                                             MinerUParseResponse resp, String imagesDir) {
        List<ParsedDocument> out = new ArrayList<>();
        if (resp.getPages() == null) return out;
        for (MinerUPage page : resp.getPages()) {
            int pageNo = page.getPageNo();
            List<MinerUBlock> blocks = page.getBlocks() != null ? page.getBlocks() : List.of();
            boolean pageHasTextBlocks = blocks.stream()
                    .anyMatch(b -> "text".equals(b.getType()));
            int blockIdx = 0;
            for (MinerUBlock block : blocks) {
                blockIdx++;
                String type = block.getType();
                if (type == null) continue;
                switch (type) {
                    case "text":
                        out.add(buildText(fileName, sourceUrl, pageNo, blockIdx, block));
                        break;
                    case "table":
                        out.add(buildTable(fileName, sourceUrl, pageNo, blockIdx, block));
                        break;
                    case "image":
                        ParsedDocument imgDoc =
                                buildImage(fileName, sourceUrl, pageNo, blockIdx, block,
                                        pageHasTextBlocks, imagesDir);
                        if (imgDoc != null) out.add(imgDoc);
                        break;
                    default:
                        log.debug("[MinerU] 忽略未知 block 类型: {}", type);
                }
            }
        }
        return out;
    }

    private ParsedDocument buildText(String fileName, String sourceUrl, int pageNo,
                                     int blockIdx, MinerUBlock block) {
        String content = block.getText() != null ? block.getText() : "";
        return ParsedDocument.builder()
                .docId(fileName + "-p" + pageNo + "-b" + blockIdx)
                .title(extractTitle(content, fileName + " 第" + pageNo + "页"))
                .content(content)
                .sourceUrl(sourceUrl)
                .pageNumber(pageNo)
                .section("第" + pageNo + "页-正文")
                .contentType("pdf")
                .contentHash(sha256(content))
                .metadata(baseMeta("text"))
                .build();
    }

    private ParsedDocument buildTable(String fileName, String sourceUrl, int pageNo,
                                      int blockIdx, MinerUBlock block) {
        String content = block.getText() != null ? block.getText() : "";
        Map<String, String> meta = baseMeta("table");
        if (block.getTableCaption() != null && !block.getTableCaption().isBlank()) {
            meta.put("pdf.tableCaption", block.getTableCaption());
        }
        String title = block.getTableCaption() != null && !block.getTableCaption().isBlank()
                ? block.getTableCaption()
                : extractTitle(content, fileName + " 表格");
        return ParsedDocument.builder()
                .docId(fileName + "-p" + pageNo + "-b" + blockIdx)
                .title("表格: " + title)
                .content(content)
                .sourceUrl(sourceUrl)
                .pageNumber(pageNo)
                .section("第" + pageNo + "页-表格")
                .contentType("pdf-table")
                .contentHash(sha256(content))
                .metadata(meta)
                .build();
    }

    private ParsedDocument buildImage(String fileName, String sourceUrl, int pageNo,
                                      int blockIdx, MinerUBlock block, boolean pageHasTextBlocks,
                                      String imagesDir) {
        boolean hasCaption = block.getImageCaption() != null && !block.getImageCaption().isBlank();
        boolean hasOcr = block.getText() != null && !block.getText().isBlank();

        // R4 映射 + R5 caption 优先
        String contentType;
        String content;
        if (hasCaption && !pageHasTextBlocks) {
            // image + caption + 无同页文本 → pdf-image-caption
            contentType = "pdf-image-caption";
            content = block.getImageCaption();
        } else if (!pageHasTextBlocks) {
            // image + 整页 ocr（图片即整页，同页无正文文本）→ pdf-ocr
            contentType = "pdf-ocr";
            content = block.getText() != null ? block.getText() : "";
        } else if (hasOcr) {
            // image + 内嵌 ocr → pdf-image-ocr
            contentType = "pdf-image-ocr";
            content = block.getText();
        } else if (hasCaption) {
            // 兜底：有 caption 但同页含文本，仍保留 caption，避免丢失
            contentType = "pdf-image-caption";
            content = block.getImageCaption();
        } else {
            // 既无 caption 也无 ocr：无可索引内容，跳过（不产出空文档）
            return null;
        }

        if (content == null || content.isBlank()) {
            return null;
        }

        Map<String, String> meta = baseMeta("image");
        if (block.getImagePath() != null && !block.getImagePath().isBlank()) {
            meta.put("pdf.imagePath", block.getImagePath());
        }
        if (hasCaption) {
            meta.put("pdf.caption", "1");
            meta.put("pdf.captionEngine", "mineru");
        }
        if (hasOcr) {
            meta.put("pdf.ocr", "1");
            meta.put("pdf.ocrChars", String.valueOf(content.length()));
            meta.put("pdf.ocrEngine", "mineru");
        }

        // ⭐ 图片向量化（P5-B）：开启 enabledImageVectorization 且视觉模型可用时，
        // 读取图片字节经模型嵌入，载体随 ParsedDocument 透传至下游 KB 视觉嵌入步骤。
        // 模型不可用 / 读图失败 / 嵌入异常均安全降级（仅索引 caption/OCR 文本，不阻断解析）。
        byte[] imageVecBytes = tryVectorizeImage(block, imagesDir);
        if (imageVecBytes != null && imageEmbeddingModel != null) {
            meta.put("pdf.imageVector", "1");
            meta.put("pdf.imageVectorDim", String.valueOf(imageEmbeddingModel.dimension()));
        }

        return ParsedDocument.builder()
                .docId(fileName + "-p" + pageNo + "-b" + blockIdx)
                .title("图片" + (hasCaption ? "说明: " : "OCR: ") + fileName + " 第" + pageNo + "页-图" + blockIdx)
                .content(content)
                .sourceUrl(sourceUrl)
                .pageNumber(pageNo)
                .section("第" + pageNo + "页-图片" + blockIdx)
                .contentType(contentType)
                .contentHash(sha256(content))
                .metadata(meta)
                .imageBytes(imageVecBytes)
                .build();
    }

    /** 读取图片字节并经视觉模型嵌入；任何异常/不可用均安全返回 null（降级为文本索引）。 */
    private byte[] tryVectorizeImage(MinerUBlock block, String imagesDir) {
        if (properties == null || !properties.isEnabledImageVectorization()) return null;
        if (imageEmbeddingModel == null || !imageEmbeddingModel.isAvailable()) return null;
        String rel = block.getImagePath();
        if (rel == null || rel.isBlank() || imagesDir == null || imagesDir.isBlank()) return null;
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(imagesDir, rel);
            if (!java.nio.file.Files.exists(p)) return null;
            byte[] bytes = java.nio.file.Files.readAllBytes(p);
            float[] vec = imageEmbeddingModel.embed(bytes);
            if (vec == null || vec.length == 0) {
                log.debug("[MinerU] 图像嵌入返回空向量（模型不可用），跳过向量化: {}",
                        block.getImagePath());
                return null;
            }
            log.info("[MinerU] 图片向量化成功: file={}, dim={}", block.getImagePath(), vec.length);
            return bytes; // 载体：下游 KB 视觉嵌入步骤据 pdf.imageVector 标记消费
        } catch (Exception e) {
            log.warn("[MinerU] 图片向量化失败，降级为文本索引: {}", e.getMessage());
            return null;
        }
    }

    /** 公共元数据打点：标记 MinerU 来源与块类型 */
    private Map<String, String> baseMeta(String blockType) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("mineru", "1");
        meta.put("mineru.block", blockType);
        return meta;
    }

    private static String extractTitle(String paragraph, String fallback) {
        String firstLine = paragraph.split("\n", 2)[0].trim();
        if (firstLine.length() > 80) firstLine = firstLine.substring(0, 80) + "...";
        return firstLine.isEmpty() ? fallback : firstLine;
    }

    private static String sha256(String text) {
        if (text == null) return "";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return String.valueOf(text.hashCode());
        }
    }
}
