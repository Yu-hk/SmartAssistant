package com.example.smartassistant.router.service.cache;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Backward-compatible routing embedding facade. The provider is now supplied
 * by the shared embedding configuration (remote in production, ONNX only when
 * explicitly enabled for offline development).
 */
@Service
public class BgeOnnxEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(BgeOnnxEmbeddingService.class);
    private final BgeEmbeddingModel delegate;

    public BgeOnnxEmbeddingService(ObjectProvider<BgeEmbeddingModel> embeddingProvider) {
        this.delegate = embeddingProvider.getIfAvailable();
    }

    @PostConstruct
    public void init() {
        if (delegate == null) {
            log.warn("[BGE] No embedding provider configured; semantic routing uses lexical fallback");
        } else {
            log.info("[BGE] Using shared provider {}, dim={}, available={}",
                    delegate.getClass().getSimpleName(), delegate.dimensions(), delegate.isAvailable());
        }
    }

    public boolean isAvailable() {
        return delegate != null && delegate.isAvailable();
    }

    public float[] embed(String text) {
        return isAvailable() ? delegate.embedding(text) : null;
    }
}
