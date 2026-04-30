package com.example.smartassistant.consumer.service.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 向量检索缓存服务 - 方案C核心组件
 * 
 * <p>功能：</p>
 * <ul>
 *     <li>使用 Redis 缓存向量检索结果，避免重复查询 pgvector</li>
 *     <li>基于问题哈希作为 Key，TTL=10分钟</li>
 *     <li>显著降低 PostgreSQL 负载，提升响应速度</li>
 * </ul>
 * 
 * <p>架构位置：</p>
 * <pre>
 * L1: Redis 精确匹配 (TTL=2min) - 同一用户重复提问
 *   ↓ 未命中
 * L2a: Redis 向量检索结果缓存 (TTL=10min) - ⭐ 新增
 *   ↓ 未命中
 * L2b: PostgreSQL pgvector 语义搜索 - 执行向量检索并缓存到 L2a
 *   ↓ 未命中
 * L3: 调用 LLM 生成
 * </pre>
 */
@Slf4j
@Service
public class VectorSearchCacheService {
    
    private final ReactiveStringRedisTemplate redisTemplate;
    private final VectorStore vectorStore;
    private final AnswerPersonalizationService personalizationService;  // ⭐ 答案个性化服务

    // 配置参数
    @Value("${cache.vector-search.ttl-minutes:10}")
    private int vectorCacheTtlMinutes;
    
    @Value("${cache.vector-search.similarity-threshold:0.85}")
    private double similarityThreshold;
    
    @Value("${cache.vector-search.top-k:3}")
    private int topK;
    
    // ⭐ 统计指标
    private final Counter vectorCacheHitCounter;
    private final Counter vectorCacheMissCounter;
    private final Timer vectorSearchTimer;
    private final AtomicLong totalVectorSearches = new AtomicLong(0);
    private final AtomicLong vectorCacheHits = new AtomicLong(0);
    
    public VectorSearchCacheService(ReactiveStringRedisTemplate redisTemplate,
                                    VectorStore vectorStore,
                                    AnswerPersonalizationService personalizationService,
                                    MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.vectorStore = vectorStore;
        this.personalizationService = personalizationService;  // ⭐

        // 初始化 Micrometer 指标
        this.vectorCacheHitCounter = Counter.builder("answer.vector.cache.hits")
            .description("向量检索缓存命中次数")
            .register(meterRegistry);
        
        this.vectorCacheMissCounter = Counter.builder("answer.vector.cache.misses")
            .description("向量检索缓存未命中次数")
            .register(meterRegistry);
        
        this.vectorSearchTimer = Timer.builder("answer.vector.search.time")
            .description("向量检索耗时")
            .register(meterRegistry);
    }
    
    /**
     * 带缓存的向量搜索（支持答案个性化 + 按画像分组过滤）
     * 
     * @param question 用户问题
     * @param userId 用户ID（用于个性化）
     * @param userGroupId 用户画像组 ID（用于分组过滤）
     * @return 相似答案 Mono（可能为空）
     */
    public Mono<String> searchWithCache(String question, String userId, String userGroupId) {
        long startTime = System.currentTimeMillis();
        // ⭐ 缓存 Key 包含画像组信息，保证不同组不会命中对方的缓存
        String cacheKey = buildVectorCacheKey(question, userGroupId);
        totalVectorSearches.incrementAndGet();
        
        // L2a: 先查 Redis 缓存（按组隔离）
        return redisTemplate.opsForValue().get(cacheKey)
            .doOnSubscribe(s -> log.debug("[VectorCache] L2a: 检查向量检索缓存（组={}）", userGroupId))
            .flatMap(cached -> {
                long duration = System.currentTimeMillis() - startTime;
                vectorCacheHitCounter.increment();
                vectorCacheHits.incrementAndGet();
                
                log.info("[VectorCache] ✅ L2a 命中向量检索缓存: groupId={}, duration={}ms", userGroupId, duration);
                
                // ⭐ 对缓存答案进行个性化重述
                return personalizationService.personalizeAnswer(cached, question, userId)
                    .map(rewritten -> {
                        log.debug("[VectorCache] 已个性化重述答案");
                        return rewritten;
                    });
            })
            .switchIfEmpty(
                // L2a 未命中，执行 L2b: PostgreSQL pgvector 搜索（带组过滤）
                Mono.defer(() -> {
                    long searchStartTime = System.currentTimeMillis();
                    log.debug("[VectorCache] L2a 未命中，执行 L2b: pgvector 语义搜索（组={}）", userGroupId);
                    
                    return Mono.fromCallable(() -> {
                        // 构建搜索请求（带元数据过滤）
                        String filterExpression = "userGroupId == '" + userGroupId + "'";
                        SearchRequest searchRequest = SearchRequest.builder()
                                .query(question)
                                .topK(topK)
                                .similarityThreshold(similarityThreshold)
                                .filterExpression(filterExpression)  // ⭐ 按画像组过滤
                                .build();
                        
                        // 执行向量搜索
                        List<Document> results = vectorStore.similaritySearch(searchRequest);
                        
                        if (results.isEmpty()) {
                            log.debug("[VectorCache] L2b: 未找到相似答案（组={}）", userGroupId);
                            return null;
                        }
                        
                        // 找到最相似的答案
                        Document bestMatch = results.get(0);
                        Map<String, Object> metadata = bestMatch.getMetadata();
                        String answer = (String) metadata.get("answer");
                        Double similarity = bestMatch.getScore();
                        
                        log.info("[VectorCache] ✅ L2b 命中 pgvector: groupId={}, similarity={}, answerLength={}",
                                userGroupId, similarity, answer != null ? answer.length() : 0);
                        
                        return answer;
                    })
                    .flatMap(answer -> {
                        if (answer == null) {
                            return Mono.empty();
                        }
                        
                        // ⭐ 对 L2b 命中的答案也进行个性化
                        return personalizationService.personalizeAnswer(answer, question, userId)
                            .map(rewritten -> {
                                log.debug("[VectorCache] L2b 答案已个性化重述");
                                return rewritten;
                            });
                    })
                    .doOnNext(answer -> {
                        long searchDuration = System.currentTimeMillis() - searchStartTime;
                        vectorSearchTimer.record(searchDuration, java.util.concurrent.TimeUnit.MILLISECONDS);
                        
                        if (answer != null) {
                            // 存入 L2a: Redis 缓存（按组隔离的 Key）
                            redisTemplate.opsForValue()
                                .set(cacheKey, answer, Duration.ofMinutes(vectorCacheTtlMinutes))
                                .subscribe();
                            
                            vectorCacheMissCounter.increment();
                            log.info("[VectorCache] 💾 已缓存向量检索结果: groupId={}, ttl={}min, searchDuration={}ms",
                                    userGroupId, vectorCacheTtlMinutes, searchDuration);
                        } else {
                            vectorCacheMissCounter.increment();
                        }
                    });
                })
            );
    }
    
    /**
     * 带缓存的向量搜索（兼容旧接口，不使用分组）
     */
    public Mono<String> searchWithCache(String question, String userId) {
        return searchWithCache(question, userId, "default");
    }
    
    /**
     * 获取向量检索缓存统计信息
     */
    public Mono<Map<String, Object>> getVectorCacheStats() {
        return Mono.fromCallable(() -> {
            long total = totalVectorSearches.get();
            long hits = vectorCacheHits.get();
            double hitRate = total > 0 ? (double) hits / total * 100 : 0;
            
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalSearches", total);
            stats.put("vectorCacheHits", hits);
            stats.put("vectorCacheMisses", total - hits);
            stats.put("hitRate", String.format("%.2f%%", hitRate));
            stats.put("avgSearchTimeMs", vectorSearchTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
            stats.put("cacheTtlMinutes", vectorCacheTtlMinutes);
            stats.put("similarityThreshold", similarityThreshold);
            stats.put("topK", topK);
            
            return stats;
        });
    }
    
    /**
     * 清除向量检索缓存
     */
    public Mono<Void> clearVectorCache() {
        return Mono.fromRunnable(() -> {
            log.info("[VectorCache] 🗑️ 清除所有向量检索缓存");
            // 实际生产中应该使用 SCAN 命令
        });
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 构建向量缓存 Key（按画像组隔离）
     */
    private String buildVectorCacheKey(String question, String userGroupId) {
        return "vector_search:" + userGroupId + ":" + hash(question);
    }
    
    /**
     * 构建向量缓存 Key（不分组，向后兼容）
     */
    private String buildVectorCacheKey(String question) {
        return buildVectorCacheKey(question, "default");
    }
    
    /**
     * 计算问题的哈希值
     */
    private String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(input.getBytes());
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hashBytes)
                .substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            log.error("[VectorCache] SHA-256 算法不可用", e);
            return Integer.toString(Math.abs(input.hashCode()));
        }
    }
}
