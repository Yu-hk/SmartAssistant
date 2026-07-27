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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * route-on-images 路由开关测试（让图片/表格经 MinerU 入索引）。
 * <p>
 * 构造「含内嵌图片 + 文本」的数字 PDF：旧路由逻辑（仅扫描件路由）对其返回 false，
 * 而开启 {@code route-on-images=true} 后应返回 true 并实际分流到 MinerU。
 * 全程使用假 MinerUClient，不依赖真装 magic-pdf / 大模型。
 * </p>
 */
class PdfParserRouterRouteOnImagesTest {

    @TempDir
    Path tempDir;

    /** 含内嵌图片 + 文本的数字 PDF：旧逻辑（仅扫描件路由）会返回 false，新 route-on-images 为 true */
    private Path buildImageWithTextPdf() throws Exception {
        Path file = tempDir.resolve("image-with-text.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            // 画一张内嵌图片（无文本，单纯图像 XObject）
            BufferedImage bim = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = bim.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 100, 100);
            g.dispose();
            PDImageXObject img = LosslessFactory.createFromImage(doc, bim);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(img, 50, 700, 100, 100);
                // 同时在页面写文字，使其不是扫描件（每页有文本）
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.beginText();
                cs.newLineAtOffset(50, 400);
                cs.showText("Document body text on every page, plus an embedded image/table.");
                cs.endText();
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private static class FakeClient implements MinerUClient {
        private final MinerUParseResponse response;
        int callCount = 0;

        FakeClient(MinerUParseResponse response) {
            this.response = response;
        }

        @Override
        public MinerUParseResponse parse(MinerUParseRequest req) throws DocumentParseException {
            callCount++;
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
    void routeOnImagesEnabledRoutesImagePdfToMinerU() throws Exception {
        Path pdf = buildImageWithTextPdf();
        MinerUProperties props = new MinerUProperties();
        props.setEnabled(true);
        props.setRouteOnImages(true);

        PdfParserRouter router = new PdfParserRouter(new FakeClient(minerUResponse()), props);
        assertTrue(router.needsMinerU(pdf.toString()),
                "routeOnImages=true 且含图片时，needsMinerU 应返回 true");
    }

    @Test
    void routeOnImagesDisabledKeepsPdfBoxDefault() throws Exception {
        Path pdf = buildImageWithTextPdf();

        // (b1) 显式关闭 route-on-images（即便 enabled=true）：保持 PDFBox 默认
        MinerUProperties explicitOff = new MinerUProperties();
        explicitOff.setEnabled(true);
        explicitOff.setRouteOnImages(false);
        PdfParserRouter routerOff = new PdfParserRouter(new FakeClient(minerUResponse()), explicitOff);
        assertEquals(false, routerOff.needsMinerU(pdf.toString()),
                "routeOnImages=false 时，含图数字 PDF 应保持 PDFBox 默认（不路由 MinerU）");

        // (b2) 默认值即关闭（enabled=false 默认）：同样不应路由 MinerU
        MinerUProperties defaults = new MinerUProperties();
        PdfParserRouter routerDefaults = new PdfParserRouter(new FakeClient(minerUResponse()), defaults);
        assertEquals(false, routerDefaults.needsMinerU(pdf.toString()),
                "默认 MinerUProperties（routeOnImages=false）不应路由 MinerU");
    }

    @Test
    void routeOnImagesParseActuallyInvokesMinerU() throws Exception {
        Path pdf = buildImageWithTextPdf();
        MinerUProperties props = new MinerUProperties();
        props.setEnabled(true);
        props.setRouteOnImages(true);
        props.setFallbackToPdfbox(true);

        PdfDocumentParser pdfSpy = Mockito.spy(new PdfDocumentParser());
        FakeClient client = new FakeClient(minerUResponse());
        PdfParserRouter router = routerWith(pdfSpy, client, props);

        List<ParsedDocument> docs = router.parse(pdf.toString());

        assertNotNull(docs);
        assertEquals(1, client.callCount,
                "routeOnImages=true 应将解析分流到 MinerU（假 client 被调用一次）");
        Mockito.verify(pdfSpy, Mockito.never()).parse(Mockito.anyString());
        assertTrue(docs.stream().anyMatch(d -> "mineru-extracted-text".equals(d.getContent())),
                "MinerU 产出的正文应进入结果");
    }
}
