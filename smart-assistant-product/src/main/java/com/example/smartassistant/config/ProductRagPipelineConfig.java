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
import com.example.smartassistant.common.rag.pipeline.RagSearchHandler;
import com.example.smartassistant.common.rag.pipeline.RagSearchPipeline;
import com.example.smartassistant.common.rag.pipeline.RerankHandler;
import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.service.search.LlmSupplementalQueryPlanner;
import com.example.smartassistant.service.search.SupplementalQueryPlanner;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Product 模块 RAG Pipeline 配置。
 *
 * <p>注册查询重写和重排序 Handler。</p>
 */
@Configuration
@EnableConfigurationProperties(NativeRagProperties.class)
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
     * Product 查询处理管线。
     *
     * <p>该 Bean 只被商品检索使用，因此由 Product 模块汇集并排序处理节点；
     * Common 仅保留可复用的管线与 Handler 抽象。</p>
     */
    @Bean
    @ConditionalOnMissingBean(RagSearchPipeline.class)
    public RagSearchPipeline productRagSearchPipeline(java.util.List<RagSearchHandler> handlers) {
        return new RagSearchPipeline(handlers);
    }

    /**
     * 查询重写 Handler。
     *
     * <p>利用供应商无关的 OpenAI 兼容模型将用户查询改写为对检索更友好的形式。
     */
    @Bean
    @ConditionalOnProperty(name = "product.rag.query-rewrite.enabled", havingValue = "true", matchIfMissing = true)
    public QueryRewriteHandler queryRewriteHandler(
            @Qualifier("openAiChatModel") ChatModel chatModel,
            AiChatService aiChatService) {
        log.info("[ProductRagPipeline] 注册 QueryRewriteHandler（openAiChatModel）");

        ChatClient chatClient = aiChatService.buildChatClient(chatModel);

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

    /** Bounded evidence-gap planner for the project-native Agentic RAG loop. */
    @Bean
    @ConditionalOnProperty(name = "product.rag.agentic.enabled", havingValue = "true", matchIfMissing = true)
    public SupplementalQueryPlanner supplementalQueryPlanner(
            @Qualifier("openAiChatModel") ChatModel chatModel,
            AiChatService aiChatService) {
        return new LlmSupplementalQueryPlanner(aiChatService.buildChatClient(chatModel));
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
            } catch (com.example.smartassistant.common.error.AgentException e) {
                // 已是标准错误码 → 直接向上冒泡
                throw e;
            } catch (Exception e) {
                // ⭐ 异常分级：嵌入服务调用失败（传输/超时/5xx）→ 归一为可重试 RAG 错误码，
                // 向上经 EmbeddingScorer → RerankHandler → 管线漏斗统一分级记录，
                // 不再被静默吞成 null/0.5 掩盖宕机。
                String snippet = text != null && text.length() > 50 ? text.substring(0, 50) + "..." : text;
                throw new com.example.smartassistant.common.error.AgentException(
                        com.example.smartassistant.common.error.AgentErrorCode.RAG_EMBEDDING_UNAVAILABLE,
                        "查询嵌入失败: " + snippet, e);
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
