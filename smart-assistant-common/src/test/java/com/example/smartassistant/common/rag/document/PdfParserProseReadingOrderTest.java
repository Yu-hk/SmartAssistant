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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * buildProse 正文阅读顺序回归测试（修复「正文阅读顺序错乱」Bug）。
 * <p>
 * 构造一页<b>双栏、每栏多行</b>的纯文本 PDF，左右两栏的 y 坐标刻意<b>错落</b>：
 * 任何左栏行与右栏行都不在同一水平线上（相邻两行 getY 差恒为 30，超过行容忍度
 * rowTol≈21.6），从而不会被 {@code detectTables} 误判为表格；且页内无图片，不触发 OCR。
 * 断言 {@code buildProse} 产出的正文按「栏0自上而下 → 栏1自上而下」的正确阅读顺序。
 * </p>
 * <p>
 * 坐标约定（关键）：本环境 {@code TextPosition.getY()} 自页面<b>顶部向下递增</b>（PDFBox 3.x
 * 的 top-origin 语义），而 {@code newLineAtOffset(x, ty)} 的 {@code ty} 以页面<b>底部</b>为原点
 * 向上递增——二者方向相反。故要把 L1 放在视觉最上方（最小 getY、最先生效），须用<b>最大</b>的 ty。
 * 修复前 {@code buildProse} 在栏内按 {@code -y}（即 getY 降序，底→顶）排序，会使栏内行序颠倒，
 * 本测试将因此失败；修复后（栏内按 {@code y} 升序，顶→底）通过。
 * </p>
 */
class PdfParserProseReadingOrderTest {

    @TempDir
    Path tempDir;

    /**
     * 程序化生成双栏、每栏三行、y 坐标错落的纯文本 PDF。
     *
     * <ul>
     *   <li>左栏（x≈50）：视觉自上而下 L1(ty=720) → L2(ty=660) → L3(ty=600)。</li>
     *   <li>右栏（x≈350）：视觉自上而下 R1(ty=690) → R2(ty=630) → R3(ty=570)，
     *       整体相对左栏错开 30（以 getY 计），避免与左栏任何一行对齐成「表格行」。</li>
     * </ul>
     * 注意 ty 越大越靠页面顶部（见类注释坐标约定）。
     */
    private Path buildStaggeredTwoColumnPdf() throws Exception {
        Path file = tempDir.resolve("prose-reading-order.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(font, 12);

                // 左栏：视觉自上而下 L1(ty=720, 顶部) → L2(ty=660) → L3(ty=600, 底部)
                float leftX = 50f;
                cs.beginText();
                cs.newLineAtOffset(leftX, 720f); cs.showText("L1"); cs.endText();
                cs.beginText();
                cs.newLineAtOffset(leftX, 660f); cs.showText("L2"); cs.endText();
                cs.beginText();
                cs.newLineAtOffset(leftX, 600f); cs.showText("L3"); cs.endText();

                // 右栏：视觉自上而下 R1(ty=690) → R2(ty=630) → R3(ty=570)，
                // ty 整体相对左栏错开 30，确保两栏不存在同一 getY 行（避免误判表格）
                float rightX = 350f;
                cs.beginText();
                cs.newLineAtOffset(rightX, 690f); cs.showText("R1"); cs.endText();
                cs.beginText();
                cs.newLineAtOffset(rightX, 630f); cs.showText("R2"); cs.endText();
                cs.beginText();
                cs.newLineAtOffset(rightX, 570f); cs.showText("R3"); cs.endText();
            }
            doc.save(file.toFile());
        }
        assertTrue(Files.exists(file));
        return file;
    }

    @Test
    void proseIsColumnThenTopToBottom() throws Exception {
        Path pdf = buildStaggeredTwoColumnPdf();
        List<ParsedDocument> docs = new PdfDocumentParser().parse(pdf.toString());
        String joined = docs.stream()
                .filter(d -> "pdf".equals(d.getContentType()))
                .map(ParsedDocument::getContent)
                .reduce("", (a, b) -> a + "\n" + b);

        // 左栏内部自上而下（视觉顶→底）：L1 → L2 → L3
        assertTrue(joined.indexOf("L1") < joined.indexOf("L2"),
                "左栏：L1 应在 L2 之前（自上而下），实际:\n" + joined);
        assertTrue(joined.indexOf("L2") < joined.indexOf("L3"),
                "左栏：L2 应在 L3 之前（自上而下），实际:\n" + joined);

        // 右栏内部自上而下（视觉顶→底）：R1 → R2 → R3
        assertTrue(joined.indexOf("R1") < joined.indexOf("R2"),
                "右栏：R1 应在 R2 之前（自上而下），实际:\n" + joined);
        assertTrue(joined.indexOf("R2") < joined.indexOf("R3"),
                "右栏：R2 应在 R3 之前（自上而下），实际:\n" + joined);

        // 栏顺序：整个左栏（栏0）应先于整个右栏（栏1）
        assertTrue(joined.indexOf("L3") < joined.indexOf("R1"),
                "双栏：左栏整体应先于右栏，实际:\n" + joined);
    }
}
