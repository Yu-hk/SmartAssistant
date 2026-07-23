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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PdfParserRouter 路由决策与 R5 caption 独占测试。
 * <ul>
 *   <li>数字 PDF（有文本）→ PDFBox（零子进程：MinerU 客户端不被调用）。</li>
 *   <li>扫描件（含图无文本）→ MinerU（R5：PDFBox 不被调用，结构性禁用其 caption/OCR）。</li>
 *   <li>MinerU 失败 + fallbackToPdfbox → 回退 PDFBox 全链路。</li>
 * </ul>
 */
class PdfParserRouterTest {

    @TempDir
    Path tempDir;

    /** 数字 PDF：仅文本，无图片 → 走 PDFBox */
    private Path buildDigitalPdf() throws Exception {
        Path file = tempDir.resolve("digital.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.beginText();
                cs.newLineAtOffset(50, 700);
                cs.showText("Digital document with extractable text for routing test.");
                cs.endText();
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /** 扫描件 PDF：仅图片（无文本）→ 走 MinerU */
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

    private static class FakeClient implements MinerUClient {
        private final MinerUParseResponse response;
        boolean throwOnCall = false;
        int callCount = 0;

        FakeClient(MinerUParseResponse response) {
            this.response = response;
        }

        @Override
        public MinerUParseResponse parse(MinerUParseRequest req) throws DocumentParseException {
            callCount++;
            if (throwOnCall) {
                throw new DocumentParseException("MinerU sidecar 不可用");
            }
            return response;
        }
    }

    private MinerUParseResponse minerUResponse() {
        MinerUParseResponse resp = new MinerUParseResponse();
        resp.setStatus("ok");
        resp.setRequestId("u-r");
        List<MinerUBlock> blocks = new ArrayList<>();
        MinerUBlock text = new MinerUBlock();
        text.setType("text");
        text.setText("mineru-extracted-text");
        blocks.add(text);
        MinerUBlock img = new MinerUBlock();
        img.setType("image");
        img.setImagePath("i/m.jpg");
        img.setImageCaption("mineru-caption");
        blocks.add(img);
        MinerUPage p = new MinerUPage(1, blocks);
        resp.setPages(List.of(p));
        return resp;
    }

    private PdfParserRouter routerWith(PdfDocumentParser pdfSpy, FakeClient client,
                                       MinerUProperties props) {
        return new PdfParserRouter(pdfSpy, new MinerUDocumentParser(client, props), props);
    }

    @Test
    void digitalPdfRoutesToPdfBoxZeroSubprocess() throws Exception {
        Path pdf = buildDigitalPdf();
        MinerUProperties props = new MinerUProperties();
        props.setFallbackToPdfbox(true);

        PdfDocumentParser pdfSpy = Mockito.spy(new PdfDocumentParser());
        FakeClient client = new FakeClient(minerUResponse());
        PdfParserRouter router = routerWith(pdfSpy, client, props);

        List<ParsedDocument> docs = router.parse(pdf.toString());

        assertTrue(docs.stream().anyMatch(d -> "pdf".equals(d.getContentType())),
                "数字 PDF 应解析出 pdf 类型文档");
        Mockito.verify(pdfSpy, Mockito.times(1)).parse(Mockito.anyString());
        // R2：数字 PDF 走 PDFBox，MinerU 客户端（即 sidecar 子进程）绝不被调用
        assertEquals(0, client.callCount, "数字 PDF 不应触发 MinerU 子进程");
    }

    @Test
    void scannedPdfRoutesToMinerU() throws Exception {
        Path pdf = buildScannedPdf();
        MinerUProperties props = new MinerUProperties();
        props.setFallbackToPdfbox(true);

        PdfDocumentParser pdfSpy = Mockito.spy(new PdfDocumentParser());
        FakeClient client = new FakeClient(minerUResponse());
        PdfParserRouter router = routerWith(pdfSpy, client, props);

        List<ParsedDocument> docs = router.parse(pdf.toString());

        assertTrue(docs.stream().anyMatch(d -> "pdf-image-caption".equals(d.getContentType())),
                "扫描件应路由到 MinerU 并产出 pdf-image-caption");
        assertEquals(1, client.callCount, "MinerU 客户端应被调用一次");
    }

    @Test
    void captionExclusiveMinerUPathDoesNotInvokePdfBox() throws Exception {
        // R5：MinerU 路径下结构性禁用 PDFBox（含其图片 caption/OCR）——PDFBox.parse 绝不被调用
        Path pdf = buildScannedPdf();
        MinerUProperties props = new MinerUProperties();
        props.setFallbackToPdfbox(true);

        PdfDocumentParser pdfSpy = Mockito.spy(new PdfDocumentParser());
        FakeClient client = new FakeClient(minerUResponse());
        PdfParserRouter router = routerWith(pdfSpy, client, props);

        router.parse(pdf.toString());

        Mockito.verify(pdfSpy, Mockito.never()).parse(Mockito.anyString());
    }

    @Test
    void minerUFailureFallsBackToPdfBox() throws Exception {
        Path pdf = buildScannedPdf();
        MinerUProperties props = new MinerUProperties();
        props.setFallbackToPdfbox(true);

        PdfDocumentParser pdfSpy = Mockito.spy(new PdfDocumentParser());
        FakeClient client = new FakeClient(null);
        client.throwOnCall = true;
        PdfParserRouter router = routerWith(pdfSpy, client, props);

        // 不应抛异常：MinerU 失败 → 回退 PDFBox
        List<ParsedDocument> docs = router.parse(pdf.toString());
        assertNotNull(docs);
        Mockito.verify(pdfSpy, Mockito.times(1)).parse(Mockito.anyString());
        assertEquals(1, client.callCount);
    }

    @Test
    void minerUFailureWithoutFallbackThrows() throws Exception {
        Path pdf = buildScannedPdf();
        MinerUProperties props = new MinerUProperties();
        props.setFallbackToPdfbox(false);

        PdfDocumentParser pdfSpy = Mockito.spy(new PdfDocumentParser());
        FakeClient client = new FakeClient(null);
        client.throwOnCall = true;
        PdfParserRouter router = routerWith(pdfSpy, client, props);

        assertThrows(DocumentParseException.class, () -> router.parse(pdf.toString()));
        Mockito.verify(pdfSpy, Mockito.never()).parse(Mockito.anyString());
    }

    @Test
    void needsMinerUDecision() throws Exception {
        Path digital = buildDigitalPdf();
        Path scanned = buildScannedPdf();
        MinerUProperties props = new MinerUProperties();
        PdfParserRouter router = new PdfParserRouter(new FakeClient(minerUResponse()), props);

        assertEquals(false, router.needsMinerU(digital.toString()), "数字 PDF 不应路由到 MinerU");
        assertEquals(true, router.needsMinerU(scanned.toString()), "扫描件应路由到 MinerU");
    }
}
