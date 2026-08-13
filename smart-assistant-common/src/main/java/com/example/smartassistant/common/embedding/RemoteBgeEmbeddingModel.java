package com.example.smartassistant.common.embedding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Compatibility adapter for legacy RAG components that still depend on the
 * concrete BGE type. In production all vectors are produced by the single
 * embedding-service process instead of loading ONNX in every application.
 */
@Component
@ConditionalOnProperty(name = "embedding.service.url")
public class RemoteBgeEmbeddingModel extends BgeEmbeddingModel {

    private final EmbeddingClient client;

    public RemoteBgeEmbeddingModel(EmbeddingClient client) {
        super();
        this.client = client;
    }

    @Override
    public float[] embedding(String text) {
        return client.embed(text);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int dimensions() {
        return client.dimensions();
    }

    @Override
    public void close() {
        // The remote client owns no ONNX resources.
    }
}
