package com.example.smartassistant.router.service.taskanalysis;

import com.example.smartassistant.router.service.cache.BgeOnnxEmbeddingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentRetrieverTest {

    @Test
    void nullEmbeddingFallsBackToKeywordRetrieval() {
        BgeOnnxEmbeddingService embeddingService = mock(BgeOnnxEmbeddingService.class);
        when(embeddingService.embed(anyString())).thenReturn(null);
        IntentRetriever retriever = new IntentRetriever(embeddingService);

        retriever.init();
        List<IntentDef> intents = retriever.retrieve("商品退货退款需要满足哪些条件？", 3);

        assertFalse(intents.isEmpty());
        assertEquals("ORDER", intents.getFirst().id());
    }
}
