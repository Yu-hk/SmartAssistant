/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document;

import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐词换行缺陷回归测试（正文行归并）。
 * <p>
 * 动态生成单栏 PDF，包含：① 一句连续中文；② 一段 C 代码（两行）。解析后断言正文
 * （contentType=pdf）中中文句与 C 函数签名均以<b>连续整行</b>形式出现，而非「一行一词」。
 * </p>
 * <p>
 * 关于「守卫强度」的说明（重要）：
 * 本测试的 PDF 以「一个文本 run = 一行」的正常方式生成（与真实单栏 PDF 一致）。
 * PDFBox 对密集文本会合并为单个 Cell，而把 token 拉开到「逐词多 Cell」又会被
 * {@code buildProse} 的列聚类切分为多列（质心聚类对长均匀序列会分裂），因此无法在生成 PDF 中
 * 同时复现「逐词多 Cell + 单列」这一修复前前置条件。故本测试是<b>正确性（后置条件）守卫</b>：
 * 确认修复后的解析器输出连贯整行，杜绝「逐词换行」退化。真正的「逐词 → 整行」改善由真实业务
 * PDF（{@code UserPdfChunkTest -Duser.pdf.path}）的 chunk 报告对比来证明。
 * </p>
 * <p>
 * 修复前 / 修复后的期望输出对比（供回归审阅）：
 * <pre>
 *   修复前（缺陷）：  频\n率\n预\n测...   /   void\ninitialization\n(\nchar\npathDem\n,\nint*\nIDCode\n)\n;
 *   修复后（正确）：  频率预测子系统模型在使用之前需利用授权管理终端进行授权。
 *                    void initialization ( char pathDem , int* IDCode ) ;   // 同栏同基线词以空格连接
 * </pre>
 * </p>
 */
class PdfParserLineMergeTest {

    /** 连续中文测试句 */
    private static final String CHINESE_SENTENCE =
            "频率预测子系统模型在使用之前需利用授权管理终端进行授权。";

    /** C 函数签名（一行一词缺陷的典型受害者） */
    private static final String C_SIGNATURE = "void initialization(char pathDem, int* IDCode);";

    /** C 第二行 */
    private static final String C_LINE2 = "double f = 10.0;";

    @TempDir
    Path tempDir;

    // ==================== 测试 ====================

    @Test
    void chineseSentenceIsMergedIntoOneLine() throws Exception {
        String content = parseGeneratedPdf();
        // 中文句作为连续整行出现：完整句子必须是 content 中的连续子串（内部无 \n 切断）
        assertTrue(content.contains(CHINESE_SENTENCE),
                "中文应作为一整行连续出现（修复后词以空格连接、不再逐字换行）。content=\n" + content);
        assertFalse(content.contains("频\n率"), "修复前中文被逐字换行；修复后不应出现逐字换行。content=\n" + content);
    }

    @Test
    void cFunctionSignatureIsNotWordPerLine() throws Exception {
        String content = parseGeneratedPdf();
        // C 函数签名作为连续整行出现（修复后同栏同基线的词以空格连接，而非逐词成行）
        String cLine = java.util.Arrays.stream(content.split("\n"))
                .filter(l -> l.contains("void") && l.contains("initialization")
                        && l.contains("char") && l.contains("pathDem") && l.contains("IDCode"))
                .findFirst()
                .orElse("");
        assertFalse(cLine.isEmpty(),
                "C 函数签名应作为连续整行出现（修复后词以空格连接、不再逐词换行）。content=\n" + content);
        assertTrue(cLine.contains("void") && cLine.contains("initialization")
                        && cLine.contains("char") && cLine.contains("pathDem") && cLine.contains("IDCode"),
                "C 函数签名应在一行内按词序出现。cLine=" + cLine);
        // 不应出现逐词换行形态
        assertFalse(content.contains("void\ninitialization"),
                "不应存在「void\\ninitialization」的逐词换行形态。content=\n" + content);
    }

    // ==================== PDF 生成 ====================

    /** 解析生成的 PDF，返回所有正文（contentType=pdf）文档的 content 拼接 */
    private String parseGeneratedPdf() throws Exception {
        Path pdf = buildPdf();
        List<ParsedDocument> docs = new PdfDocumentParser().parse(pdf.toString());
        return docs.stream()
                .filter(d -> "pdf".equals(d.getContentType()))
                .map(ParsedDocument::getContent)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    /** 程序化生成单栏 PDF：连续中文（一行）+ 两行 C 代码（各一行），模拟正常单栏文档 */
    private Path buildPdf() throws Exception {
        Path file = tempDir.resolve("line-merge.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(); // 默认 US Letter 612x792，所有 x 均 < 306 → 单栏(col 0)
            doc.addPage(page);

            // CJK 字体（Windows 自带黑体/宋体）用于中文抽取
            PDType0Font cjk = loadCjkFont(doc);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // ① 连续中文：整句一次 showText（正常文本 run，对应真实 PDF 一行 = 一个 Cell）
                cs.setFont(cjk, 12);
                cs.beginText();
                cs.newLineAtOffset(50f, 700f);
                cs.showText(CHINESE_SENTENCE);
                cs.endText();

                // ② C 代码：两行，各自一次 showText（正常文本 run）
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.COURIER), 12);
                cs.beginText();
                cs.newLineAtOffset(50f, 640f);
                cs.showText(C_SIGNATURE);
                cs.endText();
                cs.beginText();
                cs.newLineAtOffset(50f, 600f);
                cs.showText(C_LINE2);
                cs.endText();
            }
            doc.save(file.toFile());
        }
        assertTrue(Files.exists(file), "生成的测试 PDF 应存在");
        return file;
    }

    /** 定位系统 CJK 字体（优先黑体/宋体/雅黑），均不可用则抛异常以暴露环境缺失 */
    private static PDType0Font loadCjkFont(PDDocument doc) throws Exception {
        String[] candidates = {
                "C:/Windows/Fonts/simhei.ttf",
                "C:/Windows/Fonts/simsun.ttc",
                "C:/Windows/Fonts/msyh.ttc",
                "C:/Windows/Fonts/STSONG.TTF"
        };
        for (String p : candidates) {
            File f = new File(p);
            if (!f.exists()) continue;
            if (p.endsWith(".ttc")) {
                // TTC 用 RandomAccessReadBuffer 读取（默认取集合首字体，即常规字重）
                byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                return PDType0Font.load(doc, new RandomAccessReadBuffer(bytes), true, true);
            }
            // TTF 直接按文件加载
            return PDType0Font.load(doc, f);
        }
        throw new IllegalStateException("未找到可用 CJK 字体，无法生成中文抽取测试 PDF（需 Windows 字体或注入 CJK TTF）");
    }
}
