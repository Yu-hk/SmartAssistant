/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.example.smartassistant.common.embedding.EmbeddingClient;
import com.example.smartassistant.common.rag.pipeline.DedupHandler;
import com.example.smartassistant.common.rag.pipeline.EmbeddingScorer;
import com.example.smartassistant.common.rag.pipeline.QueryRewriteHandler;
import com.example.smartassistant.common.rag.pipeline.RerankHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Product 模块 RAG Pipeline 配置。
 *
 * <p>注册查询重写和重排序 Handler。</p>
 */
@Configuration
public class ProductRagPipelineConfig {

    private static final Logger log = LoggerFactory.getLogger(ProductRagPipelineConfig.class);

    @Value("${product.rag.query-rewrite.enabled:true}")
    private boolean queryRewriteEnabled;

    @Value("${product.rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${product.rag.rerank.top-k:5}")
    private int rerankTopK;

    @Value("${product.rag.dedup.enabled:true}")
    private boolean dedupEnabled;

    /**
     * 查询重写 Handler。
     *
     * <p>利用 deepSeekChatModel 将用户查询改写为对检索更友好的形式。
     * 通过 {@link QueryRewriteHandler#getOrder()} = 2 在 MultiQuery (Order=0) 之后执行。
     */
    @Bean
    @ConditionalOnProperty(name = "product.rag.query-rewrite.enabled", havingValue = "true", matchIfMissing = true)
    public QueryRewriteHandler queryRewriteHandler(
            @Qualifier("deepSeekChatModel") ChatModel chatModel) {
        log.info("[ProductRagPipeline] 注册 QueryRewriteHandler（使用 deepSeekChatModel）");

        ChatClient chatClient = ChatClient.create(chatModel);

        return new QueryRewriteHandler(prompt -> {
            try {
                String response = chatClient.prompt()
                        .user(prompt)
                        .call()
                        .content();
                return response != null ? response : "";
            } catch (Exception e) {
                log.warn("[QueryRewrite] LLM 调用失败: {}", e.getMessage());
                return "";
            }
        }, queryRewriteEnabled);
    }

    /**
     * 重排序 Handler。
     *
     * <p>使用本地嵌入服务 {@link EmbeddingClient} 对 RRF 融合后的结果做二次精排。
     * 通过 {@link RerankHandler#getOrder()} = 110 在 RrfFusionHandler (Order=100) 之后执行。
     */
    @Bean
    @ConditionalOnProperty(name = "product.rag.rerank.enabled", havingValue = "true", matchIfMissing = true)
    public RerankHandler rerankHandler(EmbeddingClient embeddingClient) {
        log.info("[ProductRagPipeline] 注册 RerankHandler（使用 EmbeddingClient）");

        EmbeddingScorer scorer = new EmbeddingScorer(text -> {
            try {
                return embeddingClient.embed(text);
            } catch (Exception e) {
                log.warn("[RerankHandler] 嵌入调用失败: {}", e.getMessage());
                return null;
            }
        });
        return new RerankHandler(scorer, rerankEnabled, rerankTopK);
    }

    /**
     * 检索结果去重 Handler。
     *
     * <p>在 RRF 融合后对结果做内容去重，移除 SHA-256 完全重复
     * 和高度相似（字符 3-gram Jaccard > 85%）的结果。
     * 通过 {@link DedupHandler#getOrder()} = 105 在中间执行。
     */
    @Bean
    @ConditionalOnProperty(name = "product.rag.dedup.enabled", havingValue = "true", matchIfMissing = true)
    public DedupHandler dedupHandler() {
        log.info("[ProductRagPipeline] 注册 DedupHandler（AGGRESSIVE 模式）");
        return new DedupHandler(dedupEnabled, DedupHandler.DedupMode.AGGRESSIVE, 0.85);
    }
}
