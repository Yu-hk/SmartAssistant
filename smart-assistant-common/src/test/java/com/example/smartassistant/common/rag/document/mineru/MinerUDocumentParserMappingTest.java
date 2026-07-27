/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.document.mineru;

import com.example.smartassistant.common.rag.document.DocumentParseException;
import com.example.smartassistant.common.rag.document.DocumentParser;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MinerU 适配器映射验证（引擎无关，注入合成响应）。
 *
 * <p>直接验证「图片文字 / 表格 进索引」的核心映射（R4）：
 * <ul>
 *   <li>text   → contentType {@code pdf}</li>
 *   <li>table  → contentType {@code pdf-table}（表格进索引）</li>
 *   <li>image + 内嵌 OCR 字（同页有正文）→ {@code pdf-image-ocr}（图片文字进索引）</li>
 *   <li>image + caption 且同页无正文 → {@code pdf-image-caption}</li>
 * </ul>
 *
 * <p>不依赖 magic-pdf 引擎：注入 {@link FakeMinerUClient} 返回构造好的
 * {@link MinerUParseResponse}，走真实 {@code parse()} → {@code mapResponse()} 链路，
 * 断言产出的 {@link ParsedDocument} 的 contentType 与 content（OCR 字必须进入 content）。
 */
class MinerUDocumentParserMappingTest {

    @Test
    void imageOcrAndTableEnterIndex() throws DocumentParseException {
        // 第1页：正文 + 表格 + 图片（含内嵌 OCR 字）
        MinerUBlock textBlock = new MinerUBlock();
        textBlock.setType("text");
        textBlock.setText("频率预测子系统用于对未来负荷进行预测。");

        MinerUBlock tableBlock = new MinerUBlock();
        tableBlock.setType("table");
        tableBlock.setText("| 参数 | 说明 |\n|---|---|\n| 采样率 | 100Hz |");
        tableBlock.setTableCaption("表1 关键参数说明");

        MinerUBlock imageOcrBlock = new MinerUBlock();
        imageOcrBlock.setType("image");
        imageOcrBlock.setText("【图片内OCR】启动后点击「授权管理」按钮完成激活");
        imageOcrBlock.setImagePath("i/p1_img1.jpg");
        imageOcrBlock.setImageCaption(null);

        MinerUPage page1 = new MinerUPage(1, List.of(textBlock, tableBlock, imageOcrBlock));

        // 第2页：仅一张带 caption 的图（同页无正文文本）
        MinerUBlock imageCaptionBlock = new MinerUBlock();
        imageCaptionBlock.setType("image");
        imageCaptionBlock.setText(null);
        imageCaptionBlock.setImagePath("i/p2_img1.jpg");
        imageCaptionBlock.setImageCaption("图2 系统整体架构示意图");

        MinerUPage page2 = new MinerUPage(2, List.of(imageCaptionBlock));

        MinerUParseResponse resp = new MinerUParseResponse();
        resp.setStatus("ok");
        resp.setPages(List.of(page1, page2));

        DocumentParser parser = new MinerUDocumentParser(new FakeMinerUClient(resp));
        List<ParsedDocument> docs = parser.parse("模型接口及使用说明.pdf");

        assertEquals(4, docs.size(), "应产出 4 个文档（正文/表格/图片OCR/图片说明）");

        Map<String, ParsedDocument> byType = new HashMap<>();
        for (ParsedDocument d : docs) {
            byType.put(d.getContentType(), d);
        }

        // 1) 正文 → pdf
        ParsedDocument textDoc = byType.get("pdf");
        assertNotNull(textDoc, "应存在 contentType=pdf 的正文块");
        assertTrue(textDoc.getContent().contains("频率预测子系统"), "正文内容应进入索引");

        // 2) 表格 → pdf-table（表格进索引）
        ParsedDocument tableDoc = byType.get("pdf-table");
        assertNotNull(tableDoc, "应存在 contentType=pdf-table 的表格块");
        assertTrue(tableDoc.getContent().contains("| 参数 | 说明 |"), "表格 Markdown 应进入索引");
        assertEquals("表1 关键参数说明", tableDoc.getMetadata().get("pdf.tableCaption"),
                "表格标题应写入元数据");

        // 3) 图片内嵌 OCR 字 → pdf-image-ocr（图片文字进索引，核心诉求）
        ParsedDocument imageOcrDoc = byType.get("pdf-image-ocr");
        assertNotNull(imageOcrDoc, "应存在 contentType=pdf-image-ocr 的图片OCR块");
        assertEquals("【图片内OCR】启动后点击「授权管理」按钮完成激活", imageOcrDoc.getContent(),
                "图片中的 OCR 文字必须进入索引 content");
        assertEquals("1", imageOcrDoc.getMetadata().get("pdf.ocr"), "应标记 ocr=1");

        // 4) 图片 caption → pdf-image-caption
        ParsedDocument imageCaptionDoc = byType.get("pdf-image-caption");
        assertNotNull(imageCaptionDoc, "应存在 contentType=pdf-image-caption 的图片说明块");
        assertEquals("图2 系统整体架构示意图", imageCaptionDoc.getContent(),
                "图片 caption 应进入索引");
        assertEquals("1", imageCaptionDoc.getMetadata().get("pdf.caption"), "应标记 caption=1");
    }

    /** 返回固定合成响应的假 Client，避免依赖真实 magic-pdf 引擎。 */
    private static class FakeMinerUClient implements MinerUClient {
        private final MinerUParseResponse fixed;

        FakeMinerUClient(MinerUParseResponse fixed) {
            this.fixed = fixed;
        }

        @Override
        public MinerUParseResponse parse(MinerUParseRequest req) throws DocumentParseException {
            return fixed;
        }
    }
}
