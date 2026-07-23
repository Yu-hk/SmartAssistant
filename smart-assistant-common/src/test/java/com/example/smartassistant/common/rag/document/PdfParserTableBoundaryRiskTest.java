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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独立边界风险探测测试（QA 回归验证补充，非本次缺陷修复的回归用例）。
 * <p>
 * 目标：独立验证缺陷 B 修复（{@code alignsWithTableStructure}）是否会误伤“跨多列但某行只填 1 列”
 * 的合法表格行。现有 {@link PdfParserMergedCellTableTest} 仅覆盖“某行 2 个独立 cell 落在不同列”
 * 的跨列合并，并未覆盖“单行被渲染为 1 个宽 cell（单列合并）”的情形。
 * </p>
 * <p>
 * 注意：本文件为<b>表征测试（characterization）</b>，断言的是<b>当前实现的实际行为</b>，
 * 用于把“残余边界风险”以可复现的方式锁定下来，供后续优化参考。断言通过不代表行为最优，
 * 仅代表当前行为已被观测记录。
 * </p>
 */
class PdfParserTableBoundaryRiskTest {

    @TempDir
    Path tempDir;

    /**
     * 场景 R1：3 列表格 + 末行为“跨全 3 列的单单元格合并汇总行”（渲染为 1 个宽 token）。
     * <p>观测当前实现：该合并行只有 1 个 cell（cells.size()==1），在 detectTables 的
     * {@code nxt.cells.size() >= 2} 守卫处即被短路，不会进入 alignsWithTableStructure，
     * 因此被排除出表格、落到正文。该行为在修复 B 前后一致（旧 alignsWithCanon 同样要求 >=2），
     * 属<b>既有（非本次引入）的边界局限</b>，非阻断。此处断言其落入正文以锁定该行为。</p>
     */
    @Test
    void r1_singleWideMergedRowFallsToProse_characterization() throws Exception {
        Path file = tempDir.resolve("r1-merged-summary.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(font, 12);
                float[] xs = {50f, 150f, 300f};
                // 表头 3 列
                String[][] header = {{"A", "B", "C"}};
                float y = 700;
                for (String[] row : header) {
                    for (int c = 0; c < row.length; c++) {
                        cs.beginText();
                        cs.newLineAtOffset(xs[c], y);
                        cs.showText(row[c]);
                        cs.endText();
                    }
                }
                y -= 30;
                // 数据行 3 列
                String[][] r1 = {{"1", "2", "3"}};
                for (String[] row : r1) {
                    for (int c = 0; c < row.length; c++) {
                        cs.beginText();
                        cs.newLineAtOffset(xs[c], y);
                        cs.showText(row[c]);
                        cs.endText();
                    }
                }
                y -= 30;
                // 末行：跨全 3 列的合并汇总行，渲染为 1 个宽 cell
                cs.beginText();
                cs.newLineAtOffset(50f, y);
                cs.showText("TOTAL (merged across all columns)");
                cs.endText();
            }
            doc.save(file.toFile());
        }
        assertTrue(Files.exists(file));

        List<ParsedDocument> docs = new PdfDocumentParser().parse(file.toString());
        List<ParsedDocument> tables = docs.stream()
                .filter(d -> "pdf-table".equals(d.getContentType())).toList();
        String joinedProse = docs.stream()
                .filter(d -> "pdf".equals(d.getContentType()))
                .map(ParsedDocument::getContent)
                .reduce("", (a, b) -> a + "\n" + b);

        // 表格仍被正确识别（表头在首行，缺陷 A 修复生效）
        assertEquals(1, tables.size(), "应识别出一张表格");
        String firstLine = tables.get(0).getContent().lines().findFirst().orElse("").trim();
        assertEquals("| A | B | C |", firstLine, "表头应在首行");

        // 表征：单列合并汇总行当前被排除到正文（既有边界局限，非阻断）
        assertTrue(joinedProse.contains("TOTAL"),
                "表征：单列合并汇总行当前落入正文（已知边界风险 R1，非阻断）");
    }

    /**
     * 场景 R2：5 列表格 + 某数据行仅填 2 列（合法稀疏行）。
     * <p>观测当前实现：该稀疏行 cells.size()==2 且 alignsWithCanon 通过，但映射到 2 个不同列，
     * covered.size()/colCount = 2/5 = 0.4 < 0.5（修复 B 的填充比例阈值），被排除出表格、落到正文。
     * 该行为为<b>修复 B 引入的边界变化</b>（旧 alignsWithCanon 仅要求对齐，会将其纳入），
     * 仅影响 >4 列的宽表稀疏行，属<b>非阻断边界风险</b>。此处断言其落入正文以锁定该行为。</p>
     */
    @Test
    void r2_sparseRowInWideTableExcluded_characterization() throws Exception {
        Path file = tempDir.resolve("r2-wide-sparse.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(font, 12);
                float[] xs = {50f, 120f, 190f, 260f, 330f};
                // 表头 5 列
                String[][] header = {{"C1", "C2", "C3", "C4", "C5"}};
                float y = 700;
                for (String[] row : header) {
                    for (int c = 0; c < row.length; c++) {
                        cs.beginText();
                        cs.newLineAtOffset(xs[c], y);
                        cs.showText(row[c]);
                        cs.endText();
                    }
                }
                y -= 30;
                // 数据行 5 列
                String[][] r1 = {{"a", "b", "c", "d", "e"}};
                for (String[] row : r1) {
                    for (int c = 0; c < row.length; c++) {
                        cs.beginText();
                        cs.newLineAtOffset(xs[c], y);
                        cs.showText(row[c]);
                        cs.endText();
                    }
                }
                y -= 30;
                // 稀疏行：仅填第 1、2 列（合法表格行）
                cs.beginText();
                cs.newLineAtOffset(xs[0], y);
                cs.showText("x");
                cs.endText();
                cs.beginText();
                cs.newLineAtOffset(xs[1], y);
                cs.showText("y");
                cs.endText();
            }
            doc.save(file.toFile());
        }
        assertTrue(Files.exists(file));

        List<ParsedDocument> docs = new PdfDocumentParser().parse(file.toString());
        List<ParsedDocument> tables = docs.stream()
                .filter(d -> "pdf-table".equals(d.getContentType())).toList();
        String joinedProse = docs.stream()
                .filter(d -> "pdf".equals(d.getContentType()))
                .map(ParsedDocument::getContent)
                .reduce("", (a, b) -> a + "\n" + b);

        // 表格仍被识别（表头在首行）
        assertEquals(1, tables.size(), "应识别出一张宽表");
        String firstLine = tables.get(0).getContent().lines().findFirst().orElse("").trim();
        assertTrue(firstLine.startsWith("| C1"), "表头应在首行: " + firstLine);

        // 表征：5 列表的 2 列稀疏行当前被排除到正文（修复 B 引入的边界变化，非阻断）
        assertTrue(joinedProse.contains("x") && joinedProse.contains("y"),
                "表征：5 列表的 2 列稀疏行当前落入正文（已知边界风险 R2，非阻断）");
    }
}
