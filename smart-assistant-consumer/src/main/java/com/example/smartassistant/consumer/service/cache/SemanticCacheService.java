/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 语义缓存服务 - Phase 2: 按用户画像分组的语义相似度检索
 * 
 * <p>功能：</p>
 * <ul>
 *     <li>将问题和答案向量化存储到 PostgreSQL pgvector</li>
 *     <li>基于语义相似度检索相似问答（阈值可配置）</li>
 *     <li>⭐ 按用户画像分组存储和检索（只有相同组用户才能共享缓存）</li>
 *     <li>时效性控制：超过最大年龄的答案不返回</li>
 *     <li>⭐ 方案C: 与 VectorSearchCacheService 集成，避免重复查询 pgvector</li>
 * </ul>
 * 
 * <p>按画像分组逻辑：</p>
 * <ul>
 *     <li>每个用户有一个画像组 ID（基于用户偏好计算）</li>
 *     <li>语义缓存按画像组 ID 存储</li>
 *     <li>检索时只返回同一画像组的缓存</li>
 *     <li>不同画像组的用户不会共享语义缓存（保持个性化）</li>
 * </ul>
 */
@Slf4j
@Service
public class SemanticCacheService {
    
    private final VectorStore vectorStore;
    private final VectorSearchCacheService vectorSearchCacheService;  // ⭐ 方案C: 向量检索缓存
    private final UserGroupingService userGroupingService;           // ⭐ 画像分组服务

    // 配置参数
    @Value("${cache.semantic.similarity-threshold:0.85}")
    private double similarityThreshold;
    
    @Value("${cache.semantic.top-k:3}")
    private int topK;
    
    @Value("${cache.semantic.max-age-hours:24}")
    private int maxAgeHours;
    
    // ⭐ 统计指标
    private final Counter semanticHitCounter;
    private final Counter semanticMissCounter;
    private final AtomicLong totalSemanticSearches = new AtomicLong(0);
    private final AtomicLong semanticHits = new AtomicLong(0);
    
    public SemanticCacheService(VectorStore vectorStore,
                                VectorSearchCacheService vectorSearchCacheService,
                                UserGroupingService userGroupingService,
                                MeterRegistry meterRegistry) {
        this.vectorStore = vectorStore;
        this.vectorSearchCacheService = vectorSearchCacheService;
        this.userGroupingService = userGroupingService;

        // 初始化 Micrometer 指标
        this.semanticHitCounter = Counter.builder("answer.semantic.cache.hits")
            .description("语义缓存命中次数")
            .register(meterRegistry);
        
        this.semanticMissCounter = Counter.builder("answer.semantic.cache.misses")
            .description("语义缓存未命中次数")
            .register(meterRegistry);
    }
    
    /**
     * 语义搜索相似答案（集成向量检索缓存 + 按画像分组）
     * 
     * @param question 用户问题
     * @param userId 用户ID（用于获取画像组）
     * @return 相似答案 Mono（可能为空）
     */
    public Mono<String> searchSimilarAnswer(String question, String userId) {
        // Do not reuse pre-governance profile-derived answers across users/groups.
        return Mono.empty();
    }
    
    /**
     * 存储答案到语义缓存（按画像分组）
     * 
     * @param question 问题
     * @param answer 答案
     * @param userId 用户ID（用于获取画像组）
     */
    public Mono<Void> storeAnswer(String question, String answer, String userId) {
        // New cache writes must use SelectiveSemanticAnswerCache's explicit scope.
        return Mono.empty();
    }
    
    /**
     * ⭐ 根据用户 ID 获取画像组 ID（委托给 UserGroupingService）
     */
    private String getUserGroupId(String userId) {
        return userGroupingService.getGroupId(userId);
    }
    
    /**
     * ⭐ 失效语义缓存的向量检索层（L2a/L2b）。
     * 知识库更新后由 {@code AnswerCacheService#invalidateAll()} 委托调用，避免返回陈旧检索结果。
     * 语义 Q/A 存储（pgvector）本身带 24h TTL 自然过期，这里仅清热点检索层。
     */
    public Mono<Void> clearVectorSearchCache() {
        return vectorSearchCacheService.clearVectorCache()
                .doOnSuccess(v -> log.info("[SemanticCache] 向量检索缓存已失效"))
                .onErrorResume(e -> {
                    log.error("[SemanticCache] 失效向量检索缓存异常（已忽略）: {}", e.getMessage());
                    return Mono.<Void>empty();
                });
    }

    /**
     * 获取语义缓存统计信息
     */
    public Mono<Map<String, Object>> getSemanticCacheStats() {        return Mono.fromCallable(() -> {
            long total = totalSemanticSearches.get();
            long hits = semanticHits.get();
            double hitRate = total > 0 ? (double) hits / total * 100 : 0;
            
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalSearches", total);
            stats.put("semanticHits", hits);
            stats.put("semanticMisses", total - hits);
            stats.put("hitRate", String.format("%.2f%%", hitRate));
            stats.put("similarityThreshold", similarityThreshold);
            stats.put("maxAgeHours", maxAgeHours);
            
            return stats;
        });
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 判断是否过期
     */
    private boolean isExpired(long timestamp) {
        long ageMs = System.currentTimeMillis() - timestamp;
        return ageMs > (maxAgeHours * 3600000L);
    }
    
    /**
     * 截断字符串（用于日志）
     */
    private String truncate(String text) {
        if (text == null) return "null";
        return text.length() > 50 ? text.substring(0, 50) + "..." : text;
    }
}
