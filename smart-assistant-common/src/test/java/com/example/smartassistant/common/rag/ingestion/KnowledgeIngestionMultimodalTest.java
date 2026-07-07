/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag.ingestion;

import com.example.smartassistant.common.rag.KnowledgeBase;
import com.example.smartassistant.common.rag.document.DocumentParseRouter;
import com.example.smartassistant.common.rag.chunking.DocumentChunker;
import com.example.smartassistant.common.rag.multimodal.ImageCaptioner;
import com.example.smartassistant.common.rag.multimodal.ImageReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class KnowledgeIngestionMultimodalTest {

    @Test
    void ingestImagesDelegatesToCaptionerAndIngests() {
        DocumentParseRouter router = mock(DocumentParseRouter.class);
        DocumentChunker chunker = mock(DocumentChunker.class);
        KnowledgeBase kb = mock(KnowledgeBase.class);

        KnowledgeIngestionService svc = new KnowledgeIngestionService(router, chunker, kb);

        // 注入可用的桩描述器
        ImageCaptioner stub = new ImageCaptioner() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String caption(ImageReference img) {
                return "图片描述-" + img.getSourceName();
            }
        };
        svc.setImageCaptioner(stub);

        int n = svc.ingestImages(
                List.of(new ImageReference("x.png", new byte[]{1, 2}, "image/png")), "t1");

        assertEquals(1, n);
        verify(kb).addDocument(ArgumentMatchers.any());
        verify(kb).reindex();
    }

    @Test
    void ingestImagesSkippedWhenCaptionerUnavailable() {
        DocumentParseRouter router = mock(DocumentParseRouter.class);
        DocumentChunker chunker = mock(DocumentChunker.class);
        KnowledgeBase kb = mock(KnowledgeBase.class);

        KnowledgeIngestionService svc = new KnowledgeIngestionService(router, chunker, kb);
        // 未注入描述器 → 默认 Noop → isAvailable=false → 跳过
        int n = svc.ingestImages(
                List.of(new ImageReference("x.png", new byte[]{1, 2}, "image/png")), "t1");

        assertEquals(0, n);
        verify(kb, Mockito.never()).addDocument(ArgumentMatchers.any());
    }
}
