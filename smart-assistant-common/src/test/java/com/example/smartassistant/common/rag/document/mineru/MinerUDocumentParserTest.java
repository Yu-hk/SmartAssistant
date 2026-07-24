/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document.mineru;

import com.example.smartassistant.common.rag.document.DocumentParseException;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MinerU 解析器单元测试：聚焦 JSON → ParsedDocument 映射（R4）与元数据打点。
 * 使用假 {@link MinerUClient} 返回构造好的响应，不依赖真实 sidecar。
 */
class MinerUDocumentParserTest {

    /** 假客户端：返回预设响应并记录调用 */
    private static class StubClient implements MinerUClient {
        private final MinerUParseResponse response;
        boolean called = false;

        StubClient(MinerUParseResponse response) {
            this.response = response;
        }

        @Override
        public MinerUParseResponse parse(MinerUParseRequest req) throws DocumentParseException {
            called = true;
            return response;
        }
    }

    private MinerUParseResponse buildResponse() {
        MinerUParseResponse resp = new MinerUParseResponse();
        resp.setStatus("ok");
        resp.setRequestId("u1");

        List<MinerUPage> pages = new ArrayList<>();

        // 第 1 页：正文 + 表格（含 caption）
        List<MinerUBlock> b1 = new ArrayList<>();
        MinerUBlock text = new MinerUBlock();
        text.setType("text");
        text.setText("正文第一段内容");
        b1.add(text);
        MinerUBlock table = new MinerUBlock();
        table.setType("table");
        table.setText("|A|B|\n|---|---|\n|1|2|");
        table.setTableCaption("表1");
        b1.add(table);
        MinerUPage p1 = new MinerUPage(1, b1);

        // 第 2 页：仅图 + caption（无同页文本）→ pdf-image-caption
        List<MinerUBlock> b2 = new ArrayList<>();
        MinerUBlock imgCap = new MinerUBlock();
        imgCap.setType("image");
        imgCap.setImagePath("i/x.jpg");
        imgCap.setImageCaption("图述");
        b2.add(imgCap);
        MinerUPage p2 = new MinerUPage(2, b2);

        // 第 3 页：正文 + 内嵌图 OCR（无 caption）→ pdf-image-ocr
        List<MinerUBlock> b3 = new ArrayList<>();
        MinerUBlock prose = new MinerUBlock();
        prose.setType("text");
        prose.setText("某正文段落");
        b3.add(prose);
        MinerUBlock imgOcr = new MinerUBlock();
        imgOcr.setType("image");
        imgOcr.setImagePath("i/y.jpg");
        imgOcr.setText("图片内文字");
        b3.add(imgOcr);
        MinerUPage p3 = new MinerUPage(3, b3);

        // 第 4 页：仅图 + OCR（无正文文本，无 caption）→ pdf-ocr（整页 ocr）
        List<MinerUBlock> b4 = new ArrayList<>();
        MinerUBlock wholeOcr = new MinerUBlock();
        wholeOcr.setType("image");
        wholeOcr.setImagePath("i/z.jpg");
        wholeOcr.setText("整页OCR文字");
        b4.add(wholeOcr);
        MinerUPage p4 = new MinerUPage(4, b4);

        pages.add(p1);
        pages.add(p2);
        pages.add(p3);
        pages.add(p4);
        resp.setPages(pages);
        return resp;
    }

    @Test
    void shouldMapAllBlockTypesWithCorrectContentType() throws Exception {
        StubClient client = new StubClient(buildResponse());
        MinerUDocumentParser parser = new MinerUDocumentParser(client, null);

        List<ParsedDocument> docs = parser.parse("/abs/sample.pdf");
        assertNotNull(docs);
        assertEquals(6, docs.size(), "应映射出 6 个文档（text+table+imgCaption+text+imgOcr+wholeOcr）");

        // 正文（第1页）
        assertTrue(docs.stream().anyMatch(d ->
                "pdf".equals(d.getContentType()) && d.getContent().contains("正文第一段内容")));
        // 表格（第1页，含 caption 元数据）
        ParsedDocument tableDoc = docs.stream()
                .filter(d -> "pdf-table".equals(d.getContentType())).findFirst().orElse(null);
        assertNotNull(tableDoc, "应识别表格文档");
        assertTrue(tableDoc.getContent().contains("|A|B|"), "表格应为 Markdown");
        assertEquals("表1", tableDoc.getMetadata().get("pdf.tableCaption"));

        // 图+caption（第2页，无同页文本）→ pdf-image-caption
        ParsedDocument capDoc = docs.stream()
                .filter(d -> "pdf-image-caption".equals(d.getContentType())).findFirst().orElse(null);
        assertNotNull(capDoc, "应识别 pdf-image-caption");
        assertEquals("图述", capDoc.getContent());
        assertEquals("i/x.jpg", capDoc.getMetadata().get("pdf.imagePath"));
        assertEquals("1", capDoc.getMetadata().get("pdf.caption"));
        assertEquals("mineru", capDoc.getMetadata().get("pdf.captionEngine"));
        assertEquals(2, capDoc.getPageNumber());

        // 内嵌图 OCR（第3页）→ pdf-image-ocr
        ParsedDocument ocrDoc = docs.stream()
                .filter(d -> "pdf-image-ocr".equals(d.getContentType())).findFirst().orElse(null);
        assertNotNull(ocrDoc, "应识别 pdf-image-ocr");
        assertEquals("图片内文字", ocrDoc.getContent());
        assertEquals("1", ocrDoc.getMetadata().get("pdf.ocr"));
        assertEquals("mineru", ocrDoc.getMetadata().get("pdf.ocrEngine"));

        // 整页 OCR（第4页）→ pdf-ocr
        ParsedDocument wholeDoc = docs.stream()
                .filter(d -> "pdf-ocr".equals(d.getContentType())).findFirst().orElse(null);
        assertNotNull(wholeDoc, "应识别 pdf-ocr");
        assertEquals("整页OCR文字", wholeDoc.getContent());
    }

    @Test
    void shouldStampMineruMetadataOnEveryDoc() throws Exception {
        StubClient client = new StubClient(buildResponse());
        MinerUDocumentParser parser = new MinerUDocumentParser(client, null);

        List<ParsedDocument> docs = parser.parse("/abs/sample.pdf");
        for (ParsedDocument d : docs) {
            assertEquals("1", d.getMetadata().get("mineru"), "每个文档应打 mineru=1");
            assertNotNull(d.getMetadata().get("mineru.block"), "每个文档应打 mineru.block");
        }
        assertTrue(client.called, "假客户端应被调用");
    }

    @Test
    void shouldSkipImageBlockWithoutContent() throws Exception {
        // 仅含一个无 caption 且无 ocr 的 image 块 → 无可索引内容，应跳过
        MinerUParseResponse resp = new MinerUParseResponse();
        resp.setStatus("ok");
        resp.setRequestId("u2");
        MinerUBlock empty = new MinerUBlock();
        empty.setType("image");
        empty.setImagePath("i/empty.jpg");
        MinerUPage p = new MinerUPage(1, List.of(empty));
        resp.setPages(List.of(p));

        StubClient client = new StubClient(resp);
        MinerUDocumentParser parser = new MinerUDocumentParser(client, null);
        List<ParsedDocument> docs = parser.parse("/abs/empty.pdf");
        assertTrue(docs.isEmpty(), "无 caption 且无 ocr 的 image 块应被跳过");
    }

    @Test
    void shouldNotProduceEmptyContentDoc() throws Exception {
        MinerUParseResponse resp = buildResponse();
        StubClient client = new StubClient(resp);
        MinerUDocumentParser parser = new MinerUDocumentParser(client, null);
        List<ParsedDocument> docs = parser.parse("/abs/sample.pdf");
        assertFalse(docs.stream().anyMatch(d -> d.getContent() == null || d.getContent().isBlank()),
                "不应产出空内容文档");
    }
}
