/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document.mineru;

import com.example.smartassistant.common.rag.chunking.DocumentChunker;
import com.example.smartassistant.common.rag.document.DocumentParser;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 真实 PDF 冒烟验证驱动（只读 / 验证，不改动任何业务源码）。
 * <p>
 * 背景：MinerU sidecar（magic-pdf）尚未安装，{@code app.rag.mineru.enabled} 默认
 * {@code false}，因此 {@link PdfParserRouter} 对"数字 PDF（含文本、无扫描页）"应直接委托
 * 给 PDFBox（零子进程）。本驱动用一份<span>程序化生成的</span>真实 PDF（正文 + 3×3 表格）
 * 走这条真实路径，验证：
 * <ul>
 *   <li>路由零回归：{@code PdfParserRouter} 对数字 PDF 委托 PDFBox；</li>
 *   <li>PDFBox 解析真实文档的成色：正文 / 表格（pdf-table）均被识别，表格文本不污染正文；</li>
 *   <li>下游 {@link DocumentChunker} 对解析结果正常分块。</li>
 * </ul>
 *
 * <p><b>运行方式（项目根 smart-assistant-common 下）：</b></p>
 * <p>注：本模块在 JDK 21 + exec-maven-plugin 3.5.1 下，{@code exec:java} 因
 * {@code MethodHandles.lookup()} 对 test-classpath 类的可见性限制而失败
 * （symbolic reference class is not accessible）。故改为以 JUnit 测试运行，输出直接打到控制台：</p>
 * <pre>{@code
 * unset SERVER__PORT
 * export JAVA_HOME="D:/Program Files/Java/jdk-21.0.6+7"
 * export PATH="$JAVA_HOME/bin:$PATH"
 * /d/maven/apache-maven-3.9.6/bin/mvn test -Dtest=RealPdfSmokeTest \
 *   -Dmaven.test.redirectTestOutputToFile=false
 * }</pre>
 *
 * <p><b>注意：</b>本驱动会生成一份样本 PDF 到
 * {@code src/test/resources/pdf/smoke-sample-with-table.pdf}，该文件<b>由本程序生成</b>，
 * 并非真实业务文档，仅用于冒烟验证。</p>
 */
class RealPdfSmokeTest {

    /** 生成的样本 PDF 输出路径（位于 test resources 下，标注为生成样本） */
    private static final Path SAMPLE_PDF =
            Paths.get("src/test/resources/pdf/smoke-sample-with-table.pdf");

    private static final int PREVIEW_LEN = 200;

    /**
     * 冒烟验证入口：生成（或复用）样本 PDF，经 PdfParserRouter（MinerU 关闭）解析，
     * 再经 DocumentChunker 分块，全部结果打印到控制台。
     */
    @Test
    void smokeParseRealPdfViaRouterThenChunk() throws Exception {
        banner("START: Real PDF Smoke Test (MinerU disabled -> PDFBox)");

        // 1) 生成（或复用）样本 PDF
        if (!Files.exists(SAMPLE_PDF)) {
            Files.createDirectories(SAMPLE_PDF.getParent());
            generateSamplePdf(SAMPLE_PDF);
            System.out.println("[fixture] 已生成样本 PDF（程序生成，非真实业务文档）: "
                    + SAMPLE_PDF.toAbsolutePath());
        } else {
            System.out.println("[fixture] 复用已存在的样本 PDF: " + SAMPLE_PDF.toAbsolutePath());
        }
        System.out.println("[fixture] 文件存在=" + Files.exists(SAMPLE_PDF)
                + " 字节数=" + Files.size(SAMPLE_PDF));

        // 2) 构造路由：MinerU 关闭（默认 enabled=false），client 传 null
        MinerUProperties props = new MinerUProperties();
        System.out.println("[config] app.rag.mineru.enabled=" + props.isEnabled()
                + " (false => 数字 PDF 走 PDFBox)");
        DocumentParser router = new PdfParserRouter((MinerUClient) null, props);

        // 3) 路由决策预扫描（透明展示）
        boolean needsMinerU = ((PdfParserRouter) router).needsMinerU(SAMPLE_PDF.toString());
        System.out.println("[router] needsMinerU(path)=" + needsMinerU
                + " => " + (needsMinerU ? "MinerU" : "PDFBox") + " 路径");

        // 4) 解析
        banner("PARSE via PdfParserRouter");
        List<ParsedDocument> parsed = router.parse(SAMPLE_PDF.toString());
        System.out.println("[parse] ParsedDocument 数量=" + parsed.size());
        for (int i = 0; i < parsed.size(); i++) {
            ParsedDocument d = parsed.get(i);
            String preview = preview(d.getContent());
            System.out.printf("[parse] #%d contentType=%-12s page=%d len=%d%n",
                    i, d.getContentType(), d.getPageNumber(),
                    d.getContent() == null ? 0 : d.getContent().length());
            System.out.println("       first " + PREVIEW_LEN + " chars: " + preview);
        }
        assertFalse(parsed.isEmpty(), "应至少解析出一个 ParsedDocument");

        // 5) 分块
        banner("CHUNK via DocumentChunker");
        DocumentChunker chunker = new DocumentChunker();
        List<KnowledgeDocument> chunks = chunker.chunk(parsed);
        System.out.println("[chunk] KnowledgeDocument 数量=" + chunks.size());
        assertFalse(chunks.isEmpty(), "应至少分出一个 KnowledgeDocument");
        if (!chunks.isEmpty()) {
            KnowledgeDocument first = chunks.get(0);
            System.out.println("[chunk] 首块 id=" + first.getId()
                    + " sourceType=" + first.getSourceType());
            System.out.println("       首块预览(" + PREVIEW_LEN + "): " + preview(first.getContent()));
        }

        banner("DONE: Real PDF Smoke Test");
    }

    /** 程序化生成"正文 + 3×3 表格 + 正文"的真实 PDF（数字 PDF，含文本、无扫描页） */
    private static void generateSamplePdf(Path file) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // 章节标题（注：标准 14 字体 Helvetica 仅支持 WinAnsi/Latin，故样本用英文，
                // 避免 CJK 字符触发 '.notdef' 编码异常；中文文档解析能力由 PdfParserTableTest 等覆盖）
                cs.setFont(font, 16);
                cs.beginText();
                cs.newLineAtOffset(50, 780);
                cs.showText("Quarterly Sales Report (Smoke Sample)");
                cs.endText();

                // 表格前正文
                cs.setFont(font, 12);
                cs.beginText();
                cs.newLineAtOffset(50, 750);
                cs.showText("This report summarizes regional sales this quarter. "
                        + "The table below lists the key metrics.");
                cs.endText();

                // 3 列对齐的表格：x = 50 / 200 / 350，行间距 30
                float[] xs = {50f, 200f, 350f};
                float y = 700;
                String[][] grid = {
                        {"Region", "Sales(M)", "QoQ"},
                        {"East", "12.8", "+12%"},
                        {"North", "9.6", "+5%"},
                        {"South", "11.1", "+9%"}
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
                cs.newLineAtOffset(50, y - 10);
                cs.showText("In conclusion, the East region leads in growth; "
                        + "consider increasing investment there next quarter.");
                cs.endText();
            }
            doc.save(file.toFile());
        }
    }

    private static String preview(String text) {
        if (text == null) return "<null>";
        String t = text.replace("\n", "\\n").replace("\r", "");
        return t.length() <= PREVIEW_LEN ? t : t.substring(0, PREVIEW_LEN) + "...";
    }

    private static void banner(String title) {
        System.out.println();
        System.out.println("==== " + title + " ====");
    }
}
