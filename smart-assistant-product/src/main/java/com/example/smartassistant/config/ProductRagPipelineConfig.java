/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.example.smartassistant.common.embedding.EmbeddingClient;
import com.example.smartassistant.common.rag.pipeline.AdaptiveRerankTopK;
import com.example.smartassistant.common.rag.pipeline.AdaptiveWeightHandler;
import com.example.smartassistant.common.rag.pipeline.DedupHandler;
import com.example.smartassistant.common.rag.pipeline.EmbeddingScorer;
import com.example.smartassistant.common.rag.pipeline.MetricsCollectorHandler;
import com.example.smartassistant.common.rag.pipeline.QueryRewriteHandler;
import com.example.smartassistant.common.rag.pipeline.RerankHandler;
import io.micrometer.core.instrument.MeterRegistry;
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

    /** 自适应重排序 Top-K 边界：事实型下限（默认 3） */
    @Value("${product.rag.rerank.top-k-min:3}")
    private int rerankTopKMin;

    /** 自适应重排序 Top-K 边界：开放式上限（默认 8） */
    @Value("${product.rag.rerank.top-k-max:8}")
    private int rerankTopKMax;

    @Value("${product.rag.dedup.enabled:true}")
    private boolean dedupEnabled;

    @Value("${product.rag.adaptive-weight.enabled:true}")
    private boolean adaptiveWeightEnabled;

    /**
     * 查询重写 Handler。
     *
     * <p>利用 deepSeekChatModel 将用户查询改写为对检索更友好的形式。
     */
    @Bean
    @ConditionalOnProperty(name = "product.rag.query-rewrite.enabled", havingValue = "true", matchIfMissing = true)
    public QueryRewriteHandler queryRewriteHandler(
            @Qualifier("deepSeekChatModel") ChatModel chatModel) {
        log.info("[ProductRagPipeline] 注册 QueryRewriteHandler（deepSeekChatModel）");

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
     * 动态自适应权重 Handler。
     *
     * <p>根据 query 长度、术语占比、口语化特征动态调整稠密/稀疏权重。
     */
    @Bean
    @ConditionalOnProperty(name = "product.rag.adaptive-weight.enabled", havingValue = "true", matchIfMissing = true)
    public AdaptiveWeightHandler adaptiveWeightHandler() {
        log.info("[ProductRagPipeline] 注册 AdaptiveWeightHandler");
        return new AdaptiveWeightHandler();
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
        return new RerankHandler(scorer, rerankEnabled, rerankTopK, adaptiveRerankTopK().asResolver());
    }

    /**
     * 自适应重排序 Top-K 解析器（文章 Q⑦「按意图类型自适应 K」）。
     * <p>{@code rerank.top-k} 作为默认/上限边界，{@code top-k-min/max} 限定事实型与开放式查询的 K 范围。</p>
     */
    @Bean
    public AdaptiveRerankTopK adaptiveRerankTopK() {
        return new AdaptiveRerankTopK(rerankTopKMin, rerankTopK, rerankTopKMax);
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

    /**
     * RAG 评估指标采集 Handler。
     *
     * <p>在 Pipeline 结束后采集 Recall@K、检索耗时等指标，
     * 通过 Micrometer 暴露给 Prometheus/Grafana。
     */
    @Bean
    @ConditionalOnProperty(name = "product.rag.metrics.enabled", havingValue = "true", matchIfMissing = true)
    public MetricsCollectorHandler metricsCollectorHandler(MeterRegistry meterRegistry) {
        log.info("[ProductRagPipeline] 注册 MetricsCollectorHandler");
        return new MetricsCollectorHandler(meterRegistry, true);
    }
}
