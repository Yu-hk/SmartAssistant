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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺陷 B 回归：紧贴表格（同左 x、垂直间距小）的多行前置段落，其末行不应被误并入表格。
 * <p>
 * 构造一份“3 列表格 + 下方紧邻的左对齐多行段落”的真实 PDF（明确标注为缺陷 B 回归 fixture）。
 * 段落的<b>首行</b>（与表格底行紧邻）被刻意拆成 2 个 token、全部落在表格第 1 列左边界容差内，
 * 复现“左对齐正文被当成表格额外一行（后两列空）”的失败路径：
 * <ul>
 *   <li>修复前（{@code alignsWithCanon} 仅要求每行每个 cell 落在某 canon 列附近）：该行 ≥2 cell 且
 *       都对齐第 1 列，会被并入表格 → 表格多出一行、正文缺失该句；</li>
 *   <li>修复后（{@code alignsWithTableStructure} 要求跨 ≥2 列且列填充比例达标）：该行只覆盖 1 列，
 *       被排除 → 表格仅含真实 3 行，正文保留该句。</li>
 * </ul>
 * 注：token 之间留 4pt 空隙（不重叠），避免 PDFBox 按坐标重排时把相邻文本交错成乱码，保证提取文本干净。
 */
class PdfParserAdjacentParagraphTableTest {

    @TempDir
    Path tempDir;

    /**
     * 生成“3 列表格 + 紧邻左对齐多行段落”的 PDF（明确标注为缺陷 B 回归 fixture）。
     * 表格列 x = 50 / 200 / 350；段落首行与表格底行垂直间距 25pt（&gt; rowTol≈21.6，保证自成一行），
     * 但水平方向 2 个 token 紧贴第 1 列左边界（x=50 与 x=72，均落在 colTol*1.5≈27 容差内）。
     */
    private Path buildAdjacentParagraphTablePdf() throws Exception {
        Path file = tempDir.resolve("adjacent-paragraph-table.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(font, 12);

                // 3 列对齐的表格：x = 50 / 200 / 350，行间距 30
                float[] xs = {50f, 200f, 350f};
                float y = 700;
                String[][] grid = {
                        {"Product", "Price (USD)", "Stock"},
                        {"Mouse", "19.50", "85"},
                        {"Keyboard", "29.99", "120"}
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

                // 紧邻表格底部的左对齐多行段落（缺陷 B 复现 fixture）：
                //  · 首行（与表格底行紧邻）：刻意拆成 2 个紧贴第 1 列左边界的 token，
                //    复现“左对齐正文被当成表格额外一行（后两列空）”的缺陷路径。
                //  · 第二行：普通句（单 cell），无论修复前后都应是正文、不会并入表格。
                float paraY1 = y - 25f; // 与表格底行垂直间距 25pt（> rowTol≈21.6，保证自成一行）
                String[] paraTokens = {"PDF", "Box"};
                float px = 50f;
                for (String tok : paraTokens) {
                    cs.beginText();
                    cs.newLineAtOffset(px, paraY1);
                    cs.showText(tok);
                    cs.endText();
                    px += 22f; // 小步前进，确保 token 仍落在第 1 列容差内且与下一 token 不重叠（提取文本干净）
                }

                float paraY2 = paraY1 - 25f; // 第二行，同样 > rowTol，自成一行
                cs.beginText();
                cs.newLineAtOffset(50f, paraY2);
                cs.showText("Paragraph preceding the table.");
                cs.endText();
            }
            doc.save(file.toFile());
        }
        assertTrue(Files.exists(file), "缺陷 B 回归 fixture 应已生成");
        return file;
    }

    @Test
    void adjacentLeftAlignedParagraphMustNotBeMergedIntoTable() throws Exception {
        Path pdf = buildAdjacentParagraphTablePdf();
        List<ParsedDocument> docs = new PdfDocumentParser().parse(pdf.toString());

        List<ParsedDocument> tables = docs.stream()
                .filter(d -> "pdf-table".equals(d.getContentType()))
                .toList();
        List<ParsedDocument> prose = docs.stream()
                .filter(d -> "pdf".equals(d.getContentType()))
                .toList();

        // 1) 只应识别出一个表格
        assertEquals(1, tables.size(), "应只识别出一个表格文档");

        // 2) 表格应只有真实 3 行（表头 + 2 数据），前置段落不得并入
        String md = tables.get(0).getContent();
        long pipeRows = md.lines()
                .filter(l -> l.trim().startsWith("| ") && !l.trim().startsWith("|---"))
                .count();
        assertEquals(3, pipeRows, "表格应只有 3 行（表头+2数据），前置段落不应并入，实际:\n" + md);
        // 修复前该行会被并入表格（首 token 落入第 1 列），修复后不在表格中
        assertFalse(md.contains("PDF"),
                "表格 Markdown 不应包含前置段落文字，实际:\n" + md);

        // 3) 前置段落文字应保留在正文中（未被表格吞掉）
        String joinedProse = prose.stream()
                .map(ParsedDocument::getContent)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(joinedProse.contains("PDF"),
                "前置段落首行应保留在正文，实际:\n" + joinedProse);
        assertTrue(joinedProse.contains("Box"),
                "前置段落首行第 2 个 token 应保留在正文，实际:\n" + joinedProse);
        assertTrue(joinedProse.contains("Paragraph preceding the table."),
                "前置段落第二行应保留在正文，实际:\n" + joinedProse);
    }
}
