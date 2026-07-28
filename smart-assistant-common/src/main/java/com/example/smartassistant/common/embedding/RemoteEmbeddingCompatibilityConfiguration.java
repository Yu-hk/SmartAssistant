package com.example.smartassistant.common.embedding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the remote embedding client through the concrete legacy type still
 * consumed by parts of the RAG pipeline.
 */
@Configuration
public class RemoteEmbeddingCompatibilityConfiguration {

    @Bean
    @ConditionalOnBean(EmbeddingClient.class)
    @ConditionalOnMissingBean(BgeEmbeddingModel.class)
    public BgeEmbeddingModel remoteBgeEmbeddingModel(EmbeddingClient client) {
        return new DelegatingBgeEmbeddingModel(client);
    }
}
