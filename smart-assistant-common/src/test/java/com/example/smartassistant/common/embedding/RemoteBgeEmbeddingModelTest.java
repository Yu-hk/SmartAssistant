package com.example.smartassistant.common.embedding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemoteBgeEmbeddingModelTest {

    @Test
    void delegatesLegacyBgeContractToRemoteClient() {
        EmbeddingClient client = mock(EmbeddingClient.class);
        float[] expected = {0.1f, 0.2f};
        when(client.embed("hello")).thenReturn(expected);
        when(client.dimensions()).thenReturn(1024);

        RemoteBgeEmbeddingModel model = new RemoteBgeEmbeddingModel(client);

        assertSame(expected, model.embedding("hello"));
        assertEquals(1024, model.dimensions());
        assertTrue(model.isAvailable());
        assertDoesNotThrow(model::close);
    }
}
