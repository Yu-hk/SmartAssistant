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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PdfDocumentParser 表格感知提取测试。
 * <p>
 * 用 PDFBox 生成包含"正文 + 3×3 表格 + 正文"的真实 PDF，
 * 验证：
 * <ul>
 *   <li>表格被重构为 Markdown（contentType=pdf-table），含表头与数据行；</li>
 *   <li>表格文本不污染正文段落；</li>
 *   <li>双栏/正文提取在引入表格后仍正常工作。</li>
 * </ul>
 */
class PdfParserTableTest {

    @TempDir
    Path tempDir;

    /** 生成含表格的 PDF：表头 + 两行数据，三列 x 对齐 */
    private Path buildTablePdf() throws Exception {
        Path file = tempDir.resolve("with-table.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(font, 12);
                // 表格前正文
                cs.beginText();
                cs.newLineAtOffset(50, 760);
                cs.showText("Pre-table prose to verify extraction is unaffected by tables.");
                cs.endText();

                // 3 列对齐的表格：x = 50 / 150 / 300，行间距 30
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

                // 表格后正文
                cs.beginText();
                cs.newLineAtOffset(50, 600);
                cs.showText("Post-table prose to verify extraction is unaffected by tables.");
                cs.endText();
            }
            doc.save(file.toFile());
        }
        assertTrue(Files.exists(file), "测试 PDF 应已生成");
        return file;
    }

    @Test
    void shouldExtractTableAsMarkdown() throws Exception {
        Path pdf = buildTablePdf();
        List<ParsedDocument> docs = new PdfDocumentParser().parse(pdf.toString());

        assertNotNull(docs);
        assertFalse(docs.isEmpty(), "应至少解析出文档");

        // 1) 找到表格文档
        List<ParsedDocument> tables = docs.stream()
                .filter(d -> "pdf-table".equals(d.getContentType()))
                .toList();
        assertEquals(1, tables.size(), "应识别出一个表格文档");

        String tableContent = tables.get(0).getContent();
        // 表头
        assertTrue(tableContent.contains("| Name | Age | City |"),
                "表格应包含 Markdown 表头，实际:\n" + tableContent);
        // 分隔行
        assertTrue(tableContent.contains("|---|---|---|"),
                "表格应包含 Markdown 分隔行，实际:\n" + tableContent);
        // 数据行
        assertTrue(tableContent.contains("| Alice | 30 | Beijing |"),
                "表格应包含第一行数据，实际:\n" + tableContent);
        assertTrue(tableContent.contains("| Bob | 25 | Shanghai |"),
                "表格应包含第二行数据，实际:\n" + tableContent);

        // 缺陷 A 回归：表头必须在 Markdown 首行（视觉自顶向下，而非沉到最底）
        String firstLine = tableContent.lines().findFirst().orElse("").trim();
        assertEquals("| Name | Age | City |", firstLine,
                "表格表头应在 Markdown 首行（自顶向下），实际首行:\n" + firstLine);
    }

    @Test
    void tableTextShouldNotPolluteProse() throws Exception {
        Path pdf = buildTablePdf();
        List<ParsedDocument> docs = new PdfDocumentParser().parse(pdf.toString());

        List<ParsedDocument> prose = docs.stream()
                .filter(d -> "pdf".equals(d.getContentType()))
                .toList();
        assertFalse(prose.isEmpty(), "应存在正文段落文档");

        String joinedProse = prose.stream()
                .map(ParsedDocument::getContent)
                .reduce("", (a, b) -> a + "\n" + b);

        // 表格单元格文本不应混入正文
        assertFalse(joinedProse.contains("Beijing"),
                "正文不应包含表格数据 Beijing，实际:\n" + joinedProse);
        assertFalse(joinedProse.contains("| Name |"),
                "正文不应包含 Markdown 表格，实际:\n" + joinedProse);

        // 但正文说明文字应保留
        assertTrue(joinedProse.contains("Pre-table prose"),
                "正文应保留表格前说明，实际:\n" + joinedProse);
        assertTrue(joinedProse.contains("Post-table prose"),
                "正文应保留表格后说明，实际:\n" + joinedProse);
    }

    @Test
    void plainProsePdfStillWorks() throws Exception {
        // 回归：无表格的纯文本 PDF 仍能正确解析为 pdf 文档
        Path file = tempDir.resolve("plain.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.beginText();
                cs.newLineAtOffset(50, 700);
                cs.showText("Plain paragraph one for regression of two-column and paragraph logic after table enhancement.");
                cs.endText();
            }
            doc.save(file.toFile());
        }
        List<ParsedDocument> docs = new PdfDocumentParser().parse(file.toString());
        assertTrue(docs.stream().anyMatch(d -> "pdf".equals(d.getContentType())),
                "纯文本 PDF 应解析出 pdf 类型文档");
        assertTrue(docs.stream().noneMatch(d -> "pdf-table".equals(d.getContentType())),
                "纯文本 PDF 不应产生表格文档");
    }
}
