package com.example.smartassistant.common.embedding;

import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Compatibility bridge for legacy RAG components that still inject the
 * concrete {@link BgeEmbeddingModel} type while inference is provided by a
 * shared remote {@link EmbeddingModel}.
 */
public final class DelegatingBgeEmbeddingModel extends BgeEmbeddingModel {

    private final EmbeddingModel delegate;

    public DelegatingBgeEmbeddingModel(EmbeddingModel delegate) {
        super(1024);
        this.delegate = delegate;
    }

    @Override
    public float[] embedding(String text) {
        return delegate.embed(text);
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void close() {
        // The remote client has no local ONNX resources to release.
    }
}
