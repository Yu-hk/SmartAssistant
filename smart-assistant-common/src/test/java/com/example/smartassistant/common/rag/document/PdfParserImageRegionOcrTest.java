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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证「区域抽图 + 去重」：
 * <ul>
 *   <li>文本页内嵌图 → 仅图片区域 OCR（pdf-image-ocr），不再整页重扫正文（无 pdf-ocr 重复）；</li>
 *   <li>图片 OCR 文本与正文重合 → 被去重丢弃；</li>
 *   <li>扫描件（无文本有图） → 仍整页 OCR 产出 pdf-ocr（向后兼容）。</li>
 * </ul>
 * 使用可控 OCR 桩（不依赖外部 Tesseract/Ollama）。
 */
class PdfParserImageRegionOcrTest {

    /** 可控 OCR 桩：始终可用，固定返回给定文本 */
    static class StubOcr implements OcrStrategy {
        private final String returns;
        StubOcr(String returns) { this.returns = returns; }
        @Override public List<String> extractText(byte[] imageData, String fileName) { return List.of(returns); }
        @Override public boolean isAvailable() { return true; }
        @Override public String engineName() { return "stub"; }
    }

    /** 构造一个含正文(可选)与内嵌图的 PDF，返回临时文件路径 */
    private Path buildPdf(String bodyText, boolean withImage) throws Exception {
        Path path = Files.createTempFile("pdf-img-", ".pdf");
        path.toFile().deleteOnExit();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                if (bodyText != null && !bodyText.isBlank()) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(bodyText);
                    cs.endText();
                }
                if (withImage) {
                    BufferedImage bim = new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = bim.createGraphics();
                    g.setColor(Color.WHITE);
                    g.fillRect(0, 0, 80, 80);
                    g.dispose();
                    PDImageXObject img = LosslessFactory.createFromImage(doc, bim);
                    cs.drawImage(img, 50, 500);
                }
            }
            doc.save(path.toFile());
        }
        return path;
    }

    @Test
    @DisplayName("文本页内嵌图：仅区域 OCR(pdf-image-ocr)，不整页重扫正文(无 pdf-ocr 重复)")
    void textPageImageRegionOcrNoWholePageDuplicate() throws Exception {
        String body = "Body paragraph on the page.";
        Path pdf = buildPdf(body, true);
        PdfDocumentParser parser = new PdfDocumentParser();
        parser.setOcrStrategy(new StubOcr("Image extracted text here."));

        List<ParsedDocument> docs = parser.parse(pdf.toString());

        boolean hasBody = docs.stream()
                .anyMatch(d -> "pdf".equals(d.getContentType()) && d.getContent().contains(body));
        boolean hasImageOcr = docs.stream()
                .anyMatch(d -> "pdf-image-ocr".equals(d.getContentType())
                        && d.getContent().contains("Image extracted text here."));
        boolean hasWholePageOcr = docs.stream()
                .anyMatch(d -> "pdf-ocr".equals(d.getContentType()));

        assertTrue(hasBody, "应含正文 pdf 块");
        assertTrue(hasImageOcr, "应含图片区域OCR块 pdf-image-ocr");
        assertFalse(hasWholePageOcr, "文本页不应再产生整页 pdf-ocr（区域化已生效）");
    }

    @Test
    @DisplayName("去重：图片 OCR 文本与正文重合时被丢弃")
    void imageOcrDuplicateWithBodyIsDropped() throws Exception {
        String body = "Body paragraph on the page.";
        Path pdf = buildPdf(body, true);
        PdfDocumentParser parser = new PdfDocumentParser();
        parser.setOcrStrategy(new StubOcr(body)); // 返回与正文完全相同

        List<ParsedDocument> docs = parser.parse(pdf.toString());

        boolean hasImageOcr = docs.stream().anyMatch(d -> "pdf-image-ocr".equals(d.getContentType()));
        boolean hasBody = docs.stream()
                .anyMatch(d -> "pdf".equals(d.getContentType()) && d.getContent().contains(body));

        assertFalse(hasImageOcr, "图片OCR文本与正文重复应被丢弃");
        assertTrue(hasBody, "正文块仍应保留");
    }

    @Test
    @DisplayName("向后兼容：扫描件(无文本有图)仍整页 OCR 产出 pdf-ocr")
    void scannedPageStillWholePageOcr() throws Exception {
        Path pdf = buildPdf("", true); // 仅图无文 → 触发整页 OCR 分支
        PdfDocumentParser parser = new PdfDocumentParser();
        parser.setOcrStrategy(new StubOcr("Scanned page text."));

        List<ParsedDocument> docs = parser.parse(pdf.toString());

        boolean hasWholePageOcr = docs.stream()
                .anyMatch(d -> "pdf-ocr".equals(d.getContentType()) && d.getContent().contains("Scanned page text."));
        boolean hasImageRegionOcr = docs.stream().anyMatch(d -> "pdf-image-ocr".equals(d.getContentType()));

        assertTrue(hasWholePageOcr, "扫描件应整页 OCR 产出 pdf-ocr");
        assertFalse(hasImageRegionOcr, "扫描件走整页OCR分支，不应产生图片区域OCR块");
    }
}
