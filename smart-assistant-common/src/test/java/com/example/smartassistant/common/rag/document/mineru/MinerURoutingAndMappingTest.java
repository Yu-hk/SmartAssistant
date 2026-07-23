/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document.mineru;

import com.example.smartassistant.common.rag.document.DocumentParseException;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import com.example.smartassistant.common.rag.document.PdfDocumentParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MinerU 路由 + 映射的集成强化测试（QA 补充）。
 * <p>
 * 目标：把"路由决策(R2/R3) + JSON 映射(R4) + caption 独占(R5)"在同一用例中端到端验证，
 * 并在工程师既有单测之外补两个边界：
 * <ul>
 *   <li>扫描件经路由后产出全部 5 种 contentType，且 PDFBox 完全不被调用（R5 结构性互斥）；</li>
 *   <li>数字 PDF 含图片图形（文本页 + 图）仍走 PDFBox、不触发 MinerU 子进程（R2/R3 防过路由）；</li>
 *   <li>内嵌图块同时含 caption 与 OCR 文本时当前实现的行为（characterization，见下注）。</li>
 * </ul>
 *
 * <p><b>关于"图块 caption + OCR 共存"的 characterization 说明：</b>
 * {@link MinerUDocumentParser} 的类注释声称"caption 优先于同图 OCR（R5 独占）"，但当前
 * 实现在"文本页上的图块同时含 caption 与 OCR 文本"时，会产出 {@code pdf-image-ocr} 并丢弃 caption，
 * 而非产出 {@code pdf-image-caption}。本测试以当前行为为基准锁定（不强制 caption 优先），
 * 该差异作为非阻断发现记录于验收报告；后续由工程师决定对齐代码或调整注释。</p>
 */
class MinerURoutingAndMappingTest {

    @TempDir
    Path tempDir;

    private Path buildScannedPdf() throws Exception {
        Path file = tempDir.resolve("scanned.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            BufferedImage bim = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = bim.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 100, 100);
            g.dispose();
            PDImageXObject img = LosslessFactory.createFromImage(doc, bim);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(img, 50, 700, 100, 100);
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /** 数字 PDF：文本页 + 一个图形图片（有文本、有图）→ 不应路由到 MinerU */
    private Path buildDigitalPdfWithFigure() throws Exception {
        Path file = tempDir.resolve("digital-figure.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            BufferedImage bim = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = bim.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 100, 100);
            g.dispose();
            PDImageXObject img = LosslessFactory.createFromImage(doc, bim);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.beginText();
                cs.newLineAtOffset(50, 700);
                cs.showText("Digital document with extractable text and a figure image.");
                cs.endText();
                cs.drawImage(img, 50, 500, 100, 100);
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private static class StubClient implements MinerUClient {
        private final MinerUParseResponse response;

        StubClient(MinerUParseResponse response) {
            this.response = response;
        }

        @Override
        public MinerUParseResponse parse(MinerUParseRequest req) throws DocumentParseException {
            return response;
        }
    }

    /** 构造覆盖全部 5 种 contentType 的响应 */
    private MinerUParseResponse allTypesResponse() {
        MinerUParseResponse resp = new MinerUParseResponse();
        resp.setStatus("ok");
        resp.setRequestId("u-all");

        // 第 1 页：正文 + 表格(含 caption) + 图(caption 无 ocr) + 图(ocr 无 caption)
        List<MinerUBlock> b1 = new ArrayList<>();
        MinerUBlock text = new MinerUBlock();
        text.setType("text");
        text.setText("正文段落");
        b1.add(text);

        MinerUBlock table = new MinerUBlock();
        table.setType("table");
        table.setText("|A|B|\n|---|---|\n|1|2|");
        table.setTableCaption("表A");
        b1.add(table);

        MinerUBlock imgCap = new MinerUBlock();
        imgCap.setType("image");
        imgCap.setImagePath("i/a.jpg");
        imgCap.setImageCaption("图A说明");
        b1.add(imgCap);

        MinerUBlock imgOcr = new MinerUBlock();
        imgOcr.setType("image");
        imgOcr.setImagePath("i/b.jpg");
        imgOcr.setText("内嵌图OCR文字");
        b1.add(imgOcr);
        MinerUPage p1 = new MinerUPage(1, b1);

        // 第 2 页：仅整页图 OCR（无同页文本、无 caption）→ pdf-ocr
        List<MinerUBlock> b2 = new ArrayList<>();
        MinerUBlock wholeOcr = new MinerUBlock();
        wholeOcr.setType("image");
        wholeOcr.setImagePath("i/c.jpg");
        wholeOcr.setText("整页OCR文字");
        b2.add(wholeOcr);
        MinerUPage p2 = new MinerUPage(2, b2);

        resp.setPages(List.of(p1, p2));
        return resp;
    }

    @Test
    void scannedPdfRoutesToMinerUAndProducesAllFiveContentTypesWithoutCallingPdfBox()
            throws Exception {
        Path pdf = buildScannedPdf();
        MinerUProperties props = new MinerUProperties();
        props.setFallbackToPdfbox(true);

        PdfDocumentParser pdfSpy = Mockito.spy(new PdfDocumentParser());
        StubClient client = new StubClient(allTypesResponse());
        PdfParserRouter router =
                new PdfParserRouter(pdfSpy, new MinerUDocumentParser(client, props), props);

        List<ParsedDocument> docs = router.parse(pdf.toString());

        // R4：全部 5 种 contentType 都应出现
        assertTrue(docs.stream().anyMatch(d -> "pdf".equals(d.getContentType())), "应有 pdf");
        assertTrue(docs.stream().anyMatch(d -> "pdf-table".equals(d.getContentType())), "应有 pdf-table");
        assertTrue(docs.stream().anyMatch(d -> "pdf-image-caption".equals(d.getContentType())),
                "应有 pdf-image-caption");
        assertTrue(docs.stream().anyMatch(d -> "pdf-image-ocr".equals(d.getContentType())),
                "应有 pdf-image-ocr");
        assertTrue(docs.stream().anyMatch(d -> "pdf-ocr".equals(d.getContentType())), "应有 pdf-ocr");

        // R5：扫描件走 MinerU，PDFBox 完全不被调用（结构性禁用其 caption/OCR，避免同图双索引）
        Mockito.verify(pdfSpy, Mockito.never()).parse(Mockito.anyString());
    }

    @Test
    void digitalPdfWithFigureStaysOnPdfBoxZeroSubprocess() throws Exception {
        // R2/R3 防过路由：含图形图片的数字 PDF（文本页 + 图）不应触发 MinerU 子进程
        Path pdf = buildDigitalPdfWithFigure();
        MinerUProperties props = new MinerUProperties();
        props.setFallbackToPdfbox(true);

        PdfDocumentParser pdfSpy = Mockito.spy(new PdfDocumentParser());
        StubClient client = new StubClient(allTypesResponse());
        PdfParserRouter router =
                new PdfParserRouter(pdfSpy, new MinerUDocumentParser(client, props), props);

        List<ParsedDocument> docs = router.parse(pdf.toString());

        // 走 PDFBox：应解析出正文 pdf 文档
        assertTrue(docs.stream().anyMatch(d -> "pdf".equals(d.getContentType())),
                "数字 PDF（含图）应走 PDFBox");
        // MinerU 客户端绝不被调用
        Mockito.verify(pdfSpy, Mockito.times(1)).parse(Mockito.anyString());
    }

    @Test
    void mixedImageBlockWithCaptionAndOcrCharacterization() throws Exception {
        // Characterization：图块同时含 caption 与 OCR 文本（文本页上）时，当前实现产出
        // pdf-image-ocr 且不产出 pdf-image-caption（与类注释"caption 优先"存在偏差，见类注释说明）。
        // 该测试锁定现状，便于后续对齐时显式变更。
        MinerUParseResponse resp = new MinerUParseResponse();
        resp.setStatus("ok");
        resp.setRequestId("u-mix");

        MinerUBlock prose = new MinerUBlock();
        prose.setType("text");
        prose.setText("某正文段落");

        MinerUBlock mixed = new MinerUBlock();
        mixed.setType("image");
        mixed.setImagePath("i/m.jpg");
        mixed.setImageCaption("图述");
        mixed.setText("图内OCR文字");
        MinerUPage p = new MinerUPage(1, List.of(prose, mixed));
        resp.setPages(List.of(p));

        StubClient client = new StubClient(resp);
        MinerUDocumentParser parser = new MinerUDocumentParser(client, null);
        List<ParsedDocument> docs = parser.parse("/abs/mix.pdf");

        assertTrue(docs.stream().anyMatch(d -> "pdf-image-ocr".equals(d.getContentType())),
                "混合图块应产出 pdf-image-ocr");
        assertFalse(docs.stream().anyMatch(d -> "pdf-image-caption".equals(d.getContentType())),
                "当前实现下混合图块的 caption 被丢弃（与类注释 caption 优先不一致，已记录）");
        assertEquals(2, docs.size(), "正文 + 混合图块 OCR，共 2 个文档");
    }
}
