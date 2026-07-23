/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document.mineru;

import com.example.smartassistant.common.rag.document.DocumentParseException;
import com.example.smartassistant.common.rag.document.DocumentParseRouter;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * DocumentParseRouter 的 MinerU 装配测试：验证启用 MinerU 且注入客户端后，
 * "pdf" 被路由到 PdfParserRouter，且数字 PDF 零子进程（客户端不被调用）。
 */
class DocumentParseRouterMinerUTest {

    @TempDir
    Path tempDir;

    private Path buildDigitalPdf() throws Exception {
        Path file = tempDir.resolve("digital.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.beginText();
                cs.newLineAtOffset(50, 700);
                cs.showText("Digital document for router wiring test.");
                cs.endText();
            }
            doc.save(file.toFile());
        }
        return file;
    }

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

    private static class RecordingClient implements MinerUClient {
        int callCount = 0;

        @Override
        public MinerUParseResponse parse(MinerUParseRequest req) throws DocumentParseException {
            callCount++;
            MinerUParseResponse resp = new MinerUParseResponse();
            resp.setStatus("ok");
            resp.setRequestId(req.getRequestId());
            MinerUBlock b = new MinerUBlock();
            b.setType("text");
            b.setText("mineru-wired-text");
            resp.setPages(List.of(new MinerUPage(1, List.of(b))));
            return resp;
        }
    }

    @Test
    void enabledMinerURoutesPdfToRouterAndDigitalPdfZeroSubprocess() throws Exception {
        Path digital = buildDigitalPdf();
        Path scanned = buildScannedPdf();

        MinerUProperties props = new MinerUProperties();
        props.setEnabled(true);
        RecordingClient client = new RecordingClient();

        DocumentParseRouter router = new DocumentParseRouter(props, client);

        // 数字 PDF → PDFBox，客户端（sidecar）不被调用
        List<ParsedDocument> digitalDocs = router.parse(digital.toString());
        assertTrue(digitalDocs.stream().anyMatch(d -> "pdf".equals(d.getContentType())));
        assertEquals(0, client.callCount, "启用 MinerU 时数字 PDF 仍应零子进程（走 PDFBox）");

        // 扫描件 → MinerU，客户端被调用
        List<ParsedDocument> scannedDocs = router.parse(scanned.toString());
        assertTrue(scannedDocs.stream().anyMatch(d -> "mineru-wired-text".equals(d.getContent())));
        assertEquals(1, client.callCount);
    }

    @Test
    void disabledMinerUKeepsPurePdfBoxBehavior() throws Exception {
        Path digital = buildDigitalPdf();
        // 不启用 MinerU：即便传入 client 为 null，行为也应与默认一致
        DocumentParseRouter router = new DocumentParseRouter(new MinerUProperties(), null);
        List<ParsedDocument> docs = router.parse(digital.toString());
        assertTrue(docs.stream().anyMatch(d -> "pdf".equals(d.getContentType())));
    }

    @Test
    void defaultConstructorStillWorks() throws Exception {
        Path digital = buildDigitalPdf();
        DocumentParseRouter router = new DocumentParseRouter();
        List<ParsedDocument> docs = router.parse(digital.toString());
        assertFalse(docs.isEmpty());
    }
}
