/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */
package com.example.smartassistant.common.rag.multimodal;

import com.example.smartassistant.common.rag.KnowledgeBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class MultimodalIngestorTest {

    @Test
    void noopCaptionerIngestsNothing() {
        KnowledgeBase kb = mock(KnowledgeBase.class);
        MultimodalIngestor ingestor = new MultimodalIngestor(new NoopImageCaptioner(), kb);
        int n = ingestor.ingestImages(
                List.of(new ImageReference("a.png", new byte[]{1}, "image/png")), "t1");
        assertEquals(0, n);
        verify(kb, never()).addDocument(ArgumentMatchers.any());
    }

    @Test
    void stubCaptionerIngestsAll() {
        KnowledgeBase kb = mock(KnowledgeBase.class);
        ImageCaptioner stub = new ImageCaptioner() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String caption(ImageReference img) {
                return "描述:" + img.getSourceName();
            }
        };
        MultimodalIngestor ingestor = new MultimodalIngestor(stub, kb);
        int n = ingestor.ingestImages(List.of(
                new ImageReference("a.png", new byte[]{1}, "image/png"),
                new ImageReference("b.png", new byte[]{2}, "image/png")), "t1");
        assertEquals(2, n);
        verify(kb, times(2)).addDocument(ArgumentMatchers.any());
    }

    @Test
    void emptyCaptionSkipped() {
        KnowledgeBase kb = mock(KnowledgeBase.class);
        ImageCaptioner stub = new ImageCaptioner() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String caption(ImageReference img) {
                return "";
            }
        };
        MultimodalIngestor ingestor = new MultimodalIngestor(stub, kb);
        int n = ingestor.ingestImages(
                List.of(new ImageReference("a.png", new byte[]{1}, "image/png")), "t1");
        assertEquals(0, n);
        verify(kb, never()).addDocument(ArgumentMatchers.any());
    }
}
