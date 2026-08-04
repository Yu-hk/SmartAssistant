/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion;

import com.example.smartassistant.common.rag.KnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.chunking.DocumentChunker;
import com.example.smartassistant.common.rag.document.DocumentParseRouter;
import com.example.smartassistant.common.rag.document.ParsedDocument;
import com.example.smartassistant.common.rag.util.HashUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeIngestionRegressionTest {

    @Mock
    private DocumentParseRouter router;

    @Mock
    private KnowledgeBase knowledgeBase;

    @TempDir
    Path tempDir;

    @Test
    void rawChecksumUsesOriginalBinaryBytes() throws Exception {
        byte[] bytes = {(byte) 0xff, (byte) 0xfe, 0x00, 0x41, 0x42};
        Path source = tempDir.resolve("sample.docx");
        Files.write(source, bytes);
        when(router.parse(source.toString())).thenReturn(List.of(parsedDocument()));

        KnowledgeIngestionService service =
                new KnowledgeIngestionService(router, new DocumentChunker(), knowledgeBase);
        IngestionResult result = service.parseAndIngest(source.toString(), "tenant-a", "v1");

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeDocument>> captor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeBase).addDocuments(captor.capture());
        assertEquals(HashUtil.sha256HexBytes(bytes), captor.getValue().get(0).getRawChecksum());
    }

    @Test
    void piiDocumentIsSkippedOnSecondUnchangedIngestion() {
        ParsedDocument parsed = ParsedDocument.builder()
                .docId("policy-pii-001")
                .title("客户联系政策")
                .content("客户联系电话为13800138000，业务人员仅可在客户明确授权后联系。"
                        + "所有联系方式必须脱敏保存，并遵循内部隐私保护与数据访问控制规范。")
                .contentType("word")
                .category("客户政策")
                .version("v1")
                .build();
        when(router.parse(anyString())).thenReturn(List.of(parsed));

        KnowledgeIngestionService service =
                new KnowledgeIngestionService(router, new DocumentChunker(), knowledgeBase);
        IngestionResult first = service.parseAndIngest("policy.docx", "tenant-a", "v1");
        IngestionResult second = service.parseAndIngest("policy.docx", "tenant-a", "v1");

        assertTrue(first.isSuccess());
        assertTrue(second.isSkipped());
        verify(knowledgeBase, times(1)).addDocuments(org.mockito.ArgumentMatchers.anyList());
    }

    private static ParsedDocument parsedDocument() {
        return ParsedDocument.builder()
                .docId("binary-001")
                .title("二进制文档")
                .content("这是用于验证原始二进制校验和的文档内容，正文长度足够通过质量评分门禁。"
                        + "校验和必须直接基于文件字节计算，不能先按 UTF-8 解码后再计算。")
                .contentType("word")
                .category("测试文档")
                .version("v1")
                .build();
    }
}
