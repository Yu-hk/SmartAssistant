/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 答案缓存服务 - Phase 1 & 2: 多层缓存架构
 * 
 * <p>缓存层级：</p>
 * <ul>
 *     <li>L1: Redis 短期缓存（5分钟）- 同一用户重复提问</li>
 *     <li>L2: PostgreSQL pgvector 语义缓存（24小时）- 跨组用户相似问题</li>
 * </ul>
 * 
 * <p>功能：</p>
 * <ul>
 *     <li>短期缓存（5分钟）：避免同一用户短时间内重复调用 LLM</li>
 *     <li>重复提问检测：识别用户反复问同样问题的行为</li>
 *     <li>智能限流：防止恶意刷接口</li>
 *     <li>⭐ Phase 2: 语义相似度检索（按用户画像分组共享缓存）</li>
 *     <li>⭐ 缓存统计指标：命中率、平均延迟、缓存大小等</li>
 *     <li>⭐ 首次获取特殊处理：来自语义缓存的答案会进行个性化重述</li>
 * </ul>
 * 
 * <p>按画像分组逻辑：</p>
 * <ul>
 *     <li>语义缓存按用户画像分组存储</li>
 *     <li>只有相同画像组的用户才能共享语义缓存</li>
 *     <li>首次获取（L2命中）时，会进行答案个性化重述</li>
 * </ul>
 */
@Service
public class AnswerCacheService {
    
    private static final Logger log = LoggerFactory.getLogger(AnswerCacheService.class);
    
    private final ReactiveStringRedisTemplate redisTemplate;
    private final SemanticCacheService semanticCacheService;  // ⭐ Phase 2: 语义缓存
    private final AnswerPersonalizationService personalizationService;  // ⭐ 答案个性化服务

    // 缓存配置
    @Value("${cache.answer.l1-ttl-minutes:2}")  // ⭐ 从配置文件读取，默认 2 分钟
    private int l1TtlMinutes;
    
    private static final int REPEAT_WINDOW_MINUTES = 5;  // 重复检测窗口
    private static final int MAX_REPEAT_COUNT = 3;  // 最大重复次数
    
    // ⭐ 统计指标
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;
    private final Timer cacheLookupTimer;
    private final DistributionSummary answerSizeDistribution;
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    
    public AnswerCacheService(ReactiveStringRedisTemplate redisTemplate, 
                              SemanticCacheService semanticCacheService,
                              AnswerPersonalizationService personalizationService,
                              MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.semanticCacheService = semanticCacheService;  // ⭐ Phase 2
        this.personalizationService = personalizationService;  // ⭐ 答案个性化

        // ⭐ 初始化 Micrometer 指标
        this.cacheHitCounter = Counter.builder("answer.cache.hits")
            .description("答案缓存命中次数")
            .register(meterRegistry);
        
        this.cacheMissCounter = Counter.builder("answer.cache.misses")
            .description("答案缓存未命中次数")
            .register(meterRegistry);
        
        this.cacheLookupTimer = Timer.builder("answer.cache.lookup.time")
            .description("缓存查找耗时")
            .register(meterRegistry);
        
        this.answerSizeDistribution = DistributionSummary.builder("answer.cache.size")
            .description("缓存答案的大小分布")
            .baseUnit("characters")
            .register(meterRegistry);
    }
    
    /**
     * 获取带缓存的答案（完全响应式 + 多层缓存 + 按画像分组）
     * 缓存查找顺序：
     * 1. L1: Redis 短期缓存（精确匹配 userId + question）
     * 2. L2: PostgreSQL 语义缓存（相似度匹配，按画像分组）
     * 3. L3: 调用 LLM 生成新答案
     * 
     * @param userId 用户ID
     * @param question 用户问题
     * @param isFirstFetch 是否是首次获取（L2命中时需要进行个性化重述）
     * @param llmCall LLM 调用函数（缓存未命中时执行）
     * @return 答案 Mono
     */
    public Mono<String> getAnswerWithCache(String userId, String question,
                                           boolean isFirstFetch,
                                           Supplier<Mono<String>> llmCall) {
        long startTime = System.currentTimeMillis();
        String cacheKey = buildCacheKey(userId, question);
        totalRequests.incrementAndGet();
        
        // L1: 检查 Redis 短期缓存
        return redisTemplate.opsForValue().get(cacheKey)
            .doOnSubscribe(s -> log.debug("[AnswerCache] L1: 检查 Redis 缓存: userId={}", userId))
            .flatMap(cached -> {
                long duration = System.currentTimeMillis() - startTime;
                cacheLookupTimer.record(duration, java.util.concurrent.TimeUnit.MILLISECONDS);
                cacheHitCounter.increment();
                cacheHits.incrementAndGet();
                
                log.info("[AnswerCache] ✅ L1 命中 Redis 缓存: userId={}, duration={}ms",
                        userId, duration);
                
                answerSizeDistribution.record(cached.length());
                
                // ⭐ 对 L1 命中的答案进行个性化重述，避免用户看到完全相同的回复
                return personalizationService.personalizeAnswer(cached, question, userId)
                    .doOnNext(rewritten -> 
                        log.debug("[AnswerCache] L1 答案已个性化重述: userId={}", userId));
            })
            .switchIfEmpty(
                // L1 未命中，尝试 L2: 语义缓存（按画像分组）
                Mono.defer(() -> {
                    log.debug("[AnswerCache] L1 未命中，尝试 L2 语义缓存（按画像分组）");
                    
                    return semanticCacheService.searchSimilarAnswer(question, userId)
                        .flatMap(semanticResult -> {
                            if (semanticResult != null) {
                                // L2 命中
                                long duration = System.currentTimeMillis() - startTime;
                                cacheHitCounter.increment();
                                cacheHits.incrementAndGet();
                                
                                log.info("[AnswerCache] ✅ L2 命中语义缓存: userId={}, duration={}ms, isFirstFetch={}",
                                        userId, duration, isFirstFetch);
                                
                                // ⭐ 首次获取时，标记需要个性化（答案已通过 AnswerPersonalizationService 重述）
                                answerSizeDistribution.record(semanticResult.length());
                                return Mono.just(semanticResult);
                            }
                            
                            // L2 也未命中，返回 empty 触发 L3
                            return Mono.empty();
                        });
                })
            )
            .switchIfEmpty(
                // L1 & L2 都未命中，调用 LLM (L3)
                Mono.defer(() -> {
                    long missStartTime = System.currentTimeMillis();
                    log.debug("[AnswerCache] L1+L2 都未命中，调用 LLM (L3): userId={}", userId);
                    
                    return llmCall.get()
                            .publishOn(Schedulers.boundedElastic())
                        .doOnNext(answer -> {
                            long missDuration = System.currentTimeMillis() - missStartTime;
                            cacheMissCounter.increment();
                            
                            // 存入 L1: Redis 缓存
                            redisTemplate.opsForValue()
                                .set(cacheKey, answer, Duration.ofMinutes(l1TtlMinutes))
                                .subscribe();
                            
                            // 存入 L2: 语义缓存（按画像分组存储）
                            semanticCacheService.storeAnswer(question, answer, userId)
                                .subscribe();
                            
                            answerSizeDistribution.record(answer.length());
                            
                            log.info("[AnswerCache] 💾 已缓存到 L1+L2: userId={}, ttl={}min, answerLength={}, llmDuration={}ms",
                                    userId, l1TtlMinutes, answer.length(), missDuration);
                        });
                })
            );
    }

    /**
     * 获取带缓存的答案（兼容旧接口）
     */
    public Mono<String> getAnswerWithCache(String userId, String question,
                                           Supplier<Mono<String>> llmCall) {
        return getAnswerWithCache(userId, question, false, llmCall);
    }
    
    /**
     * 获取缓存统计信息（包含 L1 + L2）
     */
    public Mono<Map<String, Object>> getCacheStats() {
        long total = totalRequests.get();
        long hits = cacheHits.get();
        double hitRate = total > 0 ? (double) hits / total * 100 : 0;
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRequests", total);
        stats.put("cacheHits", hits);
        stats.put("cacheMisses", total - hits);
        stats.put("hitRate", String.format("%.2f%%", hitRate));
        stats.put("avgLookupTimeMs", cacheLookupTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
        stats.put("cacheTtlMinutes", l1TtlMinutes);
        
        // ⭐ Phase 2: 添加语义缓存统计
        return semanticCacheService.getSemanticCacheStats()
            .map(semanticStats -> {
                stats.put("semanticCache", semanticStats);
                return stats;
            });
    }
    
    /**
     * 检测是否是重复提问
     * 
     * @param userId 用户ID
     * @param question 问题
     * @return true=重复提问，false=首次提问
     */
    public Mono<Boolean> isRepeatedQuestion(String userId, String question) {
        String repeatKey = "repeat:" + userId + ":" + hash(question);
        
        return redisTemplate.hasKey(repeatKey)
            .flatMap(exists -> {
                if (Boolean.TRUE.equals(exists)) {
                    // 已存在，增加计数
                    return redisTemplate.opsForValue().increment(repeatKey)
                        .map(count -> {
                            boolean isRepeat = count <= MAX_REPEAT_COUNT;
                            if (isRepeat) {
                                log.warn("[AnswerCache] ⚠️ 检测到重复提问: userId={}, count={}", 
                                        userId, count);
                            } else {
                                log.warn("[AnswerCache] 🚫 超过最大重复次数: userId={}, count={}", 
                                        userId, count);
                            }
                            return isRepeat;
                        });
                } else {
                    // 首次提问，设置标记
                    return redisTemplate.opsForValue()
                        .set(repeatKey, "1", Duration.ofMinutes(REPEAT_WINDOW_MINUTES))
                        .thenReturn(false);
                }
            });
    }
    
    /**
     * 清除用户的所有缓存
     * 
     * @param userId 用户ID
     */
    public Mono<Void> clearUserCache(String userId) {
        String pattern = "answer:" + userId + ":*";
        log.info("[AnswerCache] 🗑️ 清除用户缓存: userId={}, pattern={}", userId, pattern);

        return redisTemplate.keys(pattern)
                .collectList()
                .flatMap(keys -> {
                    if (keys.isEmpty()) {
                        log.debug("[AnswerCache] 无缓存需要清理: userId={}", userId);
                        return Mono.<Void>empty();
                    }
                    log.info("[AnswerCache] 清理 {} 个缓存 key: userId={}", keys.size(), userId);
                    return redisTemplate.delete(keys.toArray(new String[0])).then();
                })
                .doOnError(e -> log.error("[AnswerCache] 清理缓存失败: userId={}, error={}", userId, e.getMessage()))
                .onErrorResume(e -> Mono.<Void>empty());
    }
    
    /**
     * 构建缓存 Key
     */
    private String buildCacheKey(String userId, String question) {
        return "answer:" + userId + ":" + hash(question);
    }
    
    /**
     * 计算问题的哈希值（用于生成缓存 key）
     */
    private String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(input.getBytes());
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hashBytes)
                .substring(0, 16); // 取前16位作为短哈希
        } catch (NoSuchAlgorithmException e) {
            // 降级方案：直接使用字符串（不推荐，但保证可用性）
            log.error("[AnswerCache] SHA-256 算法不可用，使用降级方案", e);
            return Integer.toString(Math.abs(input.hashCode()));
        }
    }
}
