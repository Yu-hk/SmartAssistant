/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证「图片语义说明（caption）」：
 * <ul>
 *   <li>文本页内嵌图 + caption 策略可用 → 产出 pdf-image-caption 文档（含 pdf.caption 指标）；</li>
 *   <li>caption 策略不可用（默认 Noop）→ 不产出 pdf-image-caption（优雅降级，不阻断）；</li>
 *   <li>caption 与区域 OCR 互补：同一嵌入图可同时产出 pdf-image-ocr 与 pdf-image-caption。</li>
 * </ul>
 * 使用可控 caption 桩（不依赖外部 Ollama）。
 */
class PdfParserImageCaptionTest {

    /** 可控 caption 桩：始终可用，固定返回给定描述 */
    static class StubCaption implements ImageCaptionStrategy {
        private final String returns;
        StubCaption(String returns) { this.returns = returns; }
        @Override public String caption(byte[] imageData, String fileName) { return returns; }
        @Override public boolean isAvailable() { return true; }
        @Override public String engineName() { return "stub"; }
    }

    /** 构造一个含正文与内嵌图的 PDF，返回临时文件路径 */
    private Path buildPdf() throws Exception {
        Path path = Files.createTempFile("pdf-cap-", ".pdf");
        path.toFile().deleteOnExit();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Figure 1 shows the architecture.");
                cs.endText();
                BufferedImage bim = new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = bim.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, 80, 80);
                g.dispose();
                PDImageXObject img = LosslessFactory.createFromImage(doc, bim);
                cs.drawImage(img, 50, 500);
            }
            doc.save(path.toFile());
        }
        return path;
    }

    @Test
    @DisplayName("文本页内嵌图 + caption 可用 → 产出 pdf-image-caption 文档")
    void embeddedImageProducesCaption() throws Exception {
        Path pdf = buildPdf();
        PdfDocumentParser parser = new PdfDocumentParser();
        parser.setImageCaptionStrategy(new StubCaption("该示意图展示了系统分层架构。"));

        List<ParsedDocument> docs = parser.parse(pdf.toString());

        List<ParsedDocument> caps = docs.stream()
                .filter(d -> "pdf-image-caption".equals(d.getContentType()))
                .toList();
        assertFalse(caps.isEmpty(), "应产出图片语义说明文档");
        assertTrue(caps.get(0).getContent().contains("该示意图展示了系统分层架构。"));
        Map<String, String> meta = caps.get(0).getMetadata();
        assertEquals("1", meta.get("pdf.caption"), "应标注 pdf.caption=1");
        assertEquals("stub", meta.get("pdf.captionEngine"));
    }

    @Test
    @DisplayName("caption 不可用（默认 Noop）→ 不产出 pdf-image-caption（优雅降级）")
    void noCaptionWhenStrategyUnavailable() throws Exception {
        Path pdf = buildPdf();
        PdfDocumentParser parser = new PdfDocumentParser(); // 默认 NoopImageCaptionStrategy

        List<ParsedDocument> docs = parser.parse(pdf.toString());

        boolean hasCaption = docs.stream().anyMatch(d -> "pdf-image-caption".equals(d.getContentType()));
        assertFalse(hasCaption, "默认无 caption 策略时不应产出 pdf-image-caption");
    }

    @Test
    @DisplayName("caption 与区域 OCR 互补：同一嵌入图同时产出 pdf-image-ocr 与 pdf-image-caption")
    void captionAndOcrComplementary() throws Exception {
        Path pdf = buildPdf();
        PdfDocumentParser parser = new PdfDocumentParser();
        parser.setOcrStrategy(new PdfParserImageRegionOcrTest.StubOcr("Image text."));
        parser.setImageCaptionStrategy(new StubCaption("该图说明了部署拓扑。"));

        List<ParsedDocument> docs = parser.parse(pdf.toString());

        boolean hasOcr = docs.stream().anyMatch(d -> "pdf-image-ocr".equals(d.getContentType())
                && d.getContent().contains("Image text."));
        boolean hasCaption = docs.stream().anyMatch(d -> "pdf-image-caption".equals(d.getContentType())
                && d.getContent().contains("该图说明了部署拓扑。"));
        assertTrue(hasOcr, "应同时产出图片区域 OCR 块");
        assertTrue(hasCaption, "应同时产出图片语义说明块");
    }
}
