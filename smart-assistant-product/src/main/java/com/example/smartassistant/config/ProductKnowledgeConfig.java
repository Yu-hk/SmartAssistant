/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.example.smartassistant.common.rag.KnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.KnowledgeRetrievalService;
import com.example.smartassistant.common.rag.KnowledgeSeedData;
import com.example.smartassistant.common.rag.graph.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.CompletableFuture;

/**
 * 产品知识库配置——初始化基于 BGE 嵌入的知识库。
 */
@Configuration
public class ProductKnowledgeConfig {

    private static final Logger log = LoggerFactory.getLogger(ProductKnowledgeConfig.class);

    @Value("${bge.model.path:models/bge-large-zh-v1.5.onnx}")
    private String modelPath;

    @Value("${bge.vocab.path:models/tokenizer.json}")
    private String vocabPath;

    /** Reranker 默认关闭（实验性功能） */
    @Value("${app.rag.reranker.enabled:false}")
    private boolean rerankerEnabled;

    @Bean
    @ConditionalOnMissingBean
    public BgeEmbeddingModel productBgeEmbeddingModel() {
        log.info("[ProductKnowledge] 加载 BGE 模型 (path={})", modelPath);
        return new BgeEmbeddingModel(modelPath, vocabPath);
    }

    /**
     * ⭐ 生产化知识库 Bean 已迁移至 {@code RagProductionAutoConfiguration#productKnowledgeBase}：
     * 默认 {@code auto} 模式（PG 主库 + 内存降级），无 PG 时退化为纯内存（与改造前行为一致）。
     * 此处仅保留检索服务装配，注入统一的 {@link KnowledgeBase}（类型注入，检索 API 不变）。
     */
    @Bean
    public KnowledgeRetrievalService productKnowledgeRetrievalService(KnowledgeBase productKnowledgeBase) {
        return new KnowledgeRetrievalService()
                .register(productKnowledgeBase);
    }

    /**
     * ⭐ LightRAG 种子图谱抽取（异步、best-effort）。
     *
     * <p>将产品 / 订单种子文档喂给 {@link KnowledgeGraphService} 的 LLM 实体关系抽取器，
     * 构建通用知识图谱，使 {@code LightRagSearchHandler} 在查询阶段能检索「实体-关系」上下文。
     * </p>
     *
     * <p>设计要点：</p>
     * <ul>
     *     <li><b>异步</b>：放到后台线程执行，不阻塞应用启动；Ollama 未就绪时抽取调用会失败，
     *         由 {@link KnowledgeGraphService#extractFromDocument} 内部 try/catch 兜底，不影响启动；</li>
     *     <li><b>可选</b>：通过 {@code ObjectProvider} 取得图谱 Bean，缺失则跳过；</li>
     *     <li><b>可开关</b>：{@code product.rag.lightrag.ingest-seed.enabled}（默认 true）。</li>
     * </ul>
     */
    @Bean
    @ConditionalOnProperty(name = "product.rag.lightrag.ingest-seed.enabled", havingValue = "true", matchIfMissing = true)
    public CommandLineRunner lightRagSeedGraphPopulator(ObjectProvider<KnowledgeGraphService> graphProvider) {
        return args -> {
            KnowledgeGraphService graph = graphProvider.getIfAvailable();
            if (graph == null) {
                log.info("[LightRAG] 未找到 KnowledgeGraphService Bean，跳过种子图谱抽取");
                return;
            }
            CompletableFuture.runAsync(() -> {
                try {
                    int before = graph.nodeCount();
                    for (KnowledgeDocument doc : KnowledgeSeedData.productDocuments()) {
                        graph.extractFromDocument(doc.getContent(), doc.getId(), KnowledgeSeedData.PRODUCT_KB);
                    }
                    for (KnowledgeDocument doc : KnowledgeSeedData.orderDocuments()) {
                        graph.extractFromDocument(doc.getContent(), doc.getId(), KnowledgeSeedData.ORDER_KB);
                    }
                    log.info("[LightRAG] 种子图谱抽取完成: nodes {}→{}, edges={}",
                            before, graph.nodeCount(), graph.edgeCount());
                } catch (Exception e) {
                    log.warn("[LightRAG] 种子图谱抽取异常（已忽略，图将暂为空）: {}", e.getMessage());
                }
            });
        };
    }
}
