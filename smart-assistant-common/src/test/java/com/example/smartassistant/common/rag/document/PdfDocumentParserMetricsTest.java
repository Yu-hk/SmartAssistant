/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析质量指标（metadata）测试（G4）。
 * <p>验证：
 * <ul>
 *   <li>表格文档携带 {@code pdf.table=1}；</li>
 *   <li>正文文档携带 {@code pdf.columns}；</li>
 *   <li>OCR 文档（通过桩策略）携带 {@code pdf.ocr=1 / pdf.ocrChars / pdf.ocrEngine}；</li>
 *   <li>OCR 不可用时解析不抛异常（优雅降级）。</li>
 * </ul>
 * </p>
 */
class PdfDocumentParserMetricsTest {

    @TempDir
    Path tempDir;

    /** 桩 OCR：始终返回固定文本，引擎名 stub */
    static class StubOcr implements OcrStrategy {
        @Override
        public List<String> extractText(byte[] imageData, String fileName) {
            return List.of("STUB_OCR_TEXT");
        }
        @Override
        public String engineName() {
            return "stub";
        }
    }

    private Path buildTablePdf() throws Exception {
        Path file = tempDir.resolve("metrics-table.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(font, 12);
                float[] xs = {50f, 150f, 300f};
                float y = 700;
                String[][] grid = {
                        {"Name", "Age", "City"},
                        {"Alice", "30", "Beijing"},
                        {"Bob", "25", "Shanghai"}
                };
                for (String[] row : grid) {
                    for (int c = 0; c < row.length; c++) {
                        cs.beginText();
                        cs.newLineAtOffset(xs[c], y);
                        cs.showText(row[c]);
                        cs.endText();
                    }
                    y -= 30;
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private Path buildPlainPdf() throws Exception {
        Path file = tempDir.resolve("metrics-plain.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.beginText();
                cs.newLineAtOffset(50, 700);
                cs.showText("A single column paragraph for metrics verification.");
                cs.endText();
            }
            doc.save(file.toFile());
        }
        return file;
    }

    @Test
    void tableDocumentCarriesTableMetadata() throws Exception {
        List<ParsedDocument> docs = new PdfDocumentParser().parse(buildTablePdf().toString());
        List<ParsedDocument> tables = docs.stream()
                .filter(d -> "pdf-table".equals(d.getContentType())).toList();
        assertEquals(1, tables.size());
        Map<String, String> meta = tables.get(0).getMetadata();
        assertEquals("1", meta.get("pdf.table"), "表格应标注 pdf.table=1");
        assertNotNull(meta.get("pdf.tableIndex"));
    }

    @Test
    void proseDocumentCarriesColumnMetadata() throws Exception {
        List<ParsedDocument> docs = new PdfDocumentParser().parse(buildPlainPdf().toString());
        boolean ok = docs.stream()
                .filter(d -> "pdf".equals(d.getContentType()))
                .anyMatch(d -> d.getMetadata().containsKey("pdf.columns"));
        assertTrue(ok, "正文应标注 pdf.columns");
    }

    @Test
    void ocrDocumentCarriesQualityMetadata() throws Exception {
        PdfDocumentParser parser = new PdfDocumentParser();
        parser.setOcrStrategy(new StubOcr());
        List<ParsedDocument> docs = parser.parse(buildPlainPdf().toString());

        List<ParsedDocument> ocrDocs = docs.stream()
                .filter(d -> "pdf-ocr".equals(d.getContentType())).toList();
        assertFalse(ocrDocs.isEmpty(), "桩 OCR 应产出 pdf-ocr 文档");

        ParsedDocument ocr = ocrDocs.get(0);
        Map<String, String> meta = ocr.getMetadata();
        assertEquals("1", meta.get("pdf.ocr"), "应标注 pdf.ocr=1");
        assertEquals("stub", meta.get("pdf.ocrEngine"));
        assertTrue(Integer.parseInt(meta.get("pdf.ocrChars")) > 0, "pdf.ocrChars 应大于 0");
        assertTrue(ocr.getContent().contains("STUB_OCR_TEXT"));
    }

    @Test
    void noOcrEngineDoesNotThrowOnPlainPdf() throws Exception {
        // 默认策略在本环境多为 Noop 降级；解析纯文本 PDF 不应抛异常
        List<ParsedDocument> docs = new PdfDocumentParser().parse(buildPlainPdf().toString());
        assertNotNull(docs);
    }
}
