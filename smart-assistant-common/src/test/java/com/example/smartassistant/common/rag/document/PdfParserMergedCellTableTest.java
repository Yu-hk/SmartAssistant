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
 * 合并单元格 / 跨列表格提取测试（G2 列桶算法）。
 * <p>
 * 生成"表头 3 列 + 某数据行仅 2 个单元格（模拟跨列合并）"的真实 PDF，
 * 验证列桶算法能保持正确列数、不抛异常、文本无丢失。
 * </p>
 */
class PdfParserMergedCellTableTest {

    @TempDir
    Path tempDir;

    private Path buildMergedTablePdf() throws Exception {
        Path file = tempDir.resolve("merged-table.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(font, 12);
                float[] xs = {50f, 150f, 300f};
                // 表头：3 列
                String[][] header = {{"A", "B", "C"}};
                // 数据行1：仅 2 个单元格（第 1 列与第 3 列，模拟第 2 列被合并）
                // 数据行2：3 列
                String[][][] grid = {
                        {{"A", "B", "C"}},
                        {{"1", "", "2"}},   // 渲染时在 x=50 与 x=300 落字，中间留空
                        {{"3", "4", "5"}}
                };
                float y = 700;
                for (String[][] row : grid) {
                    for (String cell : row[0]) {
                        if (cell.isEmpty()) continue;
                        // 根据 cell 内容找到对应 x：A/1/3→50, B/4→150, C/2/5→300
                        float x;
                        if (cell.equals("A") || cell.equals("1") || cell.equals("3")) x = xs[0];
                        else if (cell.equals("B") || cell.equals("4")) x = xs[1];
                        else x = xs[2];
                        cs.beginText();
                        cs.newLineAtOffset(x, y);
                        cs.showText(cell);
                        cs.endText();
                    }
                    y -= 30;
                }
            }
            doc.save(file.toFile());
        }
        assertTrue(Files.exists(file));
        return file;
    }

    @Test
    void mergedCellTableKeepsColumnCount() throws Exception {
        Path pdf = buildMergedTablePdf();
        List<ParsedDocument> docs = new PdfDocumentParser().parse(pdf.toString());

        assertNotNull(docs);
        List<ParsedDocument> tables = docs.stream()
                .filter(d -> "pdf-table".equals(d.getContentType()))
                .toList();
        assertEquals(1, tables.size(), "应识别出一张表格");

        String md = tables.get(0).getContent();
        // 表头 3 列
        assertTrue(md.contains("| A | B | C |"), "表头应为 3 列，实际:\n" + md);
        assertTrue(md.contains("|---|---|---|"), "分隔行应为 3 列，实际:\n" + md);
        // 跨列行：第 1、3 列有值，第 2 列留空
        assertTrue(md.contains("| 1 |  | 2 |"), "跨列行应保持列对齐，实际:\n" + md);
        // 普通 3 列行
        assertTrue(md.contains("| 3 | 4 | 5 |"), "普通行应完整，实际:\n" + md);

        // 合并单元格不应导致额外表格文档
        assertEquals(1, tables.size());
        // 表格不应污染正文
        assertFalse(docs.stream().anyMatch(d -> "pdf".equals(d.getContentType())
                && d.getContent().contains("| A |")), "正文不应含表格 Markdown");
    }
}
