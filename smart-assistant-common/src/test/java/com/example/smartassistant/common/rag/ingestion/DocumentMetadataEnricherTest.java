/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion;

import com.example.smartassistant.common.rag.document.ParsedDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DocumentMetadataEnricher 单元测试——验证版本/时效/分类/ACL/sourceType 绑定规则（架构 §8.2）。
 */
class DocumentMetadataEnricherTest {

    private final DocumentMetadataEnricher enricher = new DocumentMetadataEnricher();

    @Test
    void versionDerivedFromFilenameWhenMissing() {
        ParsedDocument p = ParsedDocument.builder()
                .docId("refund-policy-v3-s1")
                .title("退款政策")
                .content("正文内容足够长以通过校验。")
                .build();
        ParsedDocument e = enricher.enrich(p);
        assertEquals("v3", e.getVersion(), "无显式版本时应从文件名正则 v\\d+ 推导");
    }

    @Test
    void versionDefaultsToV1() {
        ParsedDocument p = ParsedDocument.builder()
                .docId("notice-001")
                .title("公告")
                .content("正文内容足够长以通过校验。")
                .build();
        ParsedDocument e = enricher.enrich(p);
        assertEquals("v1", e.getVersion());
    }

    @Test
    void categoryDerivedFromTitleWhenMissing() {
        ParsedDocument p = ParsedDocument.builder()
                .docId("doc-1")
                .title("退款流程说明文档")
                .content("正文内容足够长以通过校验。")
                .build();
        ParsedDocument e = enricher.enrich(p);
        assertEquals("退款流程说明文档", e.getCategory(), "无分类时由标题推断");
    }

    @Test
    void validityDerivedFromTextDates() {
        ParsedDocument p = ParsedDocument.builder()
                .docId("doc-1")
                .title("政策")
                .content("本政策生效:2026-01-01，失效:2026-12-31，请留意时间节点。")
                .build();
        ParsedDocument e = enricher.enrich(p);
        assertTrue(e.getEffectiveAt() > 0, "应从正文抽取生效时间");
        assertTrue(e.getExpireAt() > 0, "应从正文抽取失效时间");
    }

    @Test
    void toSourceTypeMapping() {
        assertEquals("PDF", DocumentMetadataEnricher.toSourceType("pdf"));
        assertEquals("WORD", DocumentMetadataEnricher.toSourceType("docx"));
        assertEquals("WORD", DocumentMetadataEnricher.toSourceType("word"));
        assertEquals("HTML", DocumentMetadataEnricher.toSourceType("html"));
        assertEquals("MARKDOWN", DocumentMetadataEnricher.toSourceType("md"));
        assertEquals("MARKDOWN", DocumentMetadataEnricher.toSourceType("markdown"));
        assertEquals("TXT", DocumentMetadataEnricher.toSourceType("txt"));
        assertEquals("", DocumentMetadataEnricher.toSourceType("unknown"));
    }

    @Test
    void allowedSourceTypes() {
        assertTrue(DocumentMetadataEnricher.isAllowedSource("PDF"));
        assertTrue(DocumentMetadataEnricher.isAllowedSource("IMAGE"));
        assertFalse(DocumentMetadataEnricher.isAllowedSource("xyz"));
        assertFalse(DocumentMetadataEnricher.isAllowedSource(""));
    }
}
