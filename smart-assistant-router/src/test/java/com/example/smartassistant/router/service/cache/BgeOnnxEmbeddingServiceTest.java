package com.example.smartassistant.router.service.cache;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BgeOnnxEmbeddingServiceTest {

    @Test
    void degradesCleanlyWithoutEmbeddingProvider() {
        @SuppressWarnings("unchecked")
        ObjectProvider<BgeEmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        BgeOnnxEmbeddingService service = new BgeOnnxEmbeddingService(provider);

        service.init();

        assertFalse(service.isAvailable());
        assertNull(service.embed("hello"));
    }

    @Test
    void delegatesToConfiguredProvider() {
        @SuppressWarnings("unchecked")
        ObjectProvider<BgeEmbeddingModel> provider = mock(ObjectProvider.class);
        BgeEmbeddingModel model = mock(BgeEmbeddingModel.class);
        float[] expected = {1f};
        when(provider.getIfAvailable()).thenReturn(model);
        when(model.isAvailable()).thenReturn(true);
        when(model.dimensions()).thenReturn(1024);
        when(model.embedding("hello")).thenReturn(expected);
        BgeOnnxEmbeddingService service = new BgeOnnxEmbeddingService(provider);

        service.init();

        assertTrue(service.isAvailable());
        assertSame(expected, service.embed("hello"));
    }
}
