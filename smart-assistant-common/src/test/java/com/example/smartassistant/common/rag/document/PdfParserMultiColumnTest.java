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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多栏（双栏 / 三栏）阅读顺序测试（G3 间隙分段）。
 * <p>
 * 验证非表格正文按 x 坐标聚类的栏数重排，左栏内容出现在右栏之前，
 * 三栏时顺序为 左 → 中 → 右。
 * </p>
 */
class PdfParserMultiColumnTest {

    @TempDir
    Path tempDir;

    private Path buildMultiColumnPdf(int columns) throws Exception {
        Path file = tempDir.resolve("multi-col-" + columns + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(font, 12);
                float pageW = page.getMediaBox().getWidth(); // 612
                float colW = pageW / columns;
                // 每栏一个独立段落，置于该栏水平中心附近
                String[] labels = {"LEFT", "MID", "RIGHT"};
                for (int c = 0; c < columns; c++) {
                    float x = colW * c + 40f;
                    cs.beginText();
                    cs.newLineAtOffset(x, 700);
                    cs.showText(labels[c] + " column paragraph");
                    cs.endText();
                }
            }
            doc.save(file.toFile());
        }
        assertTrue(Files.exists(file));
        return file;
    }

    @Test
    void twoColumnOrderIsLeftThenRight() throws Exception {
        Path pdf = buildMultiColumnPdf(2);
        List<ParsedDocument> docs = new PdfDocumentParser().parse(pdf.toString());
        String joined = docs.stream()
                .filter(d -> "pdf".equals(d.getContentType()))
                .map(ParsedDocument::getContent)
                .reduce("", (a, b) -> a + "\n" + b);

        // 2 栏使用 labels[0]=LEFT、labels[1]=MID（标签数组第 3 项未使用）
        assertTrue(joined.contains("LEFT"), "应含左栏文本; 实际:\n" + joined);
        assertTrue(joined.contains("MID"), "应含右栏(中)文本; 实际:\n" + joined);
        // 左栏出现在右栏之前（正确的阅读顺序）
        assertTrue(joined.indexOf("LEFT") < joined.indexOf("MID"),
                "双栏：左栏应先于右栏，实际:\n" + joined);
    }

    @Test
    void threeColumnOrderIsLeftMidRight() throws Exception {
        Path pdf = buildMultiColumnPdf(3);
        List<ParsedDocument> docs = new PdfDocumentParser().parse(pdf.toString());
        String joined = docs.stream()
                .filter(d -> "pdf".equals(d.getContentType()))
                .map(ParsedDocument::getContent)
                .reduce("", (a, b) -> a + "\n" + b);

        assertTrue(joined.contains("LEFT"), "应含左栏文本");
        assertTrue(joined.contains("MID"), "应含中栏文本");
        assertTrue(joined.contains("RIGHT"), "应含右栏文本");
        assertTrue(joined.indexOf("LEFT") < joined.indexOf("MID"),
                "三栏：左应先于中，实际:\n" + joined);
        assertTrue(joined.indexOf("MID") < joined.indexOf("RIGHT"),
                "三栏：中应先于右，实际:\n" + joined);
    }

    @Test
    void proseCarriesColumnCountMetadata() throws Exception {
        Path pdf = buildMultiColumnPdf(2);
        List<ParsedDocument> docs = new PdfDocumentParser().parse(pdf.toString());
        boolean hasColumnsMeta = docs.stream()
                .filter(d -> "pdf".equals(d.getContentType()))
                .anyMatch(d -> "2".equals(d.getMetadata().get("pdf.columns")));
        assertTrue(hasColumnsMeta, "双栏正文应标注 pdf.columns=2");
        assertFalse(docs.stream().anyMatch(d -> "pdf-table".equals(d.getContentType())),
                "无表格 PDF 不应产生表格文档");
    }
}
