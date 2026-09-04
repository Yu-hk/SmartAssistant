/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.cache;

import com.example.smartassistant.common.cache.KnowledgeVersionManager;
import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Consumer-side semantic answer cache placed before Router invocation.
 *
 * <p>Consumer never classifies or approves a response itself. It only stores
 * entries carrying a cache directive produced by Route after task planning and
 * quality evaluation. Consequently, a cache hit can bypass Route without
 * duplicating routing rules in Consumer.</p>
 */
@Service
public class SelectiveSemanticAnswerCache {

    static final String PRODUCT = "PRODUCT_CONSULTATION";
    static final String BUSINESS = "BUSINESS_CONSULTATION";
    private static final String PREFIX = "consumer:semantic-answer:v2:";
    private static final Logger log = LoggerFactory.getLogger(SelectiveSemanticAnswerCache.class);

    private final StringRedisTemplate redisTemplate;
    private final BgeEmbeddingModel embeddingModel;
    private final KnowledgeVersionManager knowledgeVersionManager;
    private final SemanticCacheEquivalenceVerifier equivalenceVerifier;
    private final ObjectMapper objectMapper;
    private final Duration productTtl;
    private final Duration businessTtl;
    private final double productSimilarityThreshold;
    private final double businessSimilarityThreshold;
    private final double minimumScoreMargin;
    private final double verifierConfidenceThreshold;
    private final int candidateLimit;
    private final int maxEntriesPerPartition;

    public SelectiveSemanticAnswerCache(
            StringRedisTemplate redisTemplate,
            ObjectProvider<BgeEmbeddingModel> embeddingProvider,
            ObjectProvider<SemanticCacheEquivalenceVerifier> verifierProvider,
            KnowledgeVersionManager knowledgeVersionManager,
            ObjectMapper objectMapper,
            @Value("${consumer.semantic-answer-cache.product-ttl:PT1M}") Duration productTtl,
            @Value("${consumer.semantic-answer-cache.business-ttl:PT24H}") Duration businessTtl,
            @Value("${consumer.semantic-answer-cache.product-similarity-threshold:0.76}") double productSimilarityThreshold,
            @Value("${consumer.semantic-answer-cache.business-similarity-threshold:0.94}") double businessSimilarityThreshold,
            @Value("${consumer.semantic-answer-cache.minimum-score-margin:0.03}") double minimumScoreMargin,
            @Value("${consumer.semantic-answer-cache.verifier-confidence-threshold:0.95}") double verifierConfidenceThreshold,
            @Value("${consumer.semantic-answer-cache.candidate-limit:100}") int candidateLimit,
            @Value("${consumer.semantic-answer-cache.max-entries-per-partition:500}") int maxEntriesPerPartition) {
        this.redisTemplate = redisTemplate;
        this.embeddingModel = embeddingProvider.getIfAvailable();
        this.equivalenceVerifier = verifierProvider.getIfAvailable();
        this.knowledgeVersionManager = knowledgeVersionManager;
        this.objectMapper = objectMapper;
        this.productTtl = positive(productTtl, Duration.ofMinutes(1));
        this.businessTtl = positive(businessTtl, Duration.ofHours(24));
        this.productSimilarityThreshold = bounded(productSimilarityThreshold);
        this.businessSimilarityThreshold = bounded(businessSimilarityThreshold);
        this.minimumScoreMargin = bounded(minimumScoreMargin);
        this.verifierConfidenceThreshold = bounded(verifierConfidenceThreshold);
        this.candidateLimit = Math.max(1, candidateLimit);
        this.maxEntriesPerPartition = Math.max(this.candidateLimit, maxEntriesPerPartition);
    }

    /** Searches only Route-approved product and current-version business partitions. */
    public Map<String, Object> find(long userId, String question) {
        if (userId <= 0 || question == null || question.isBlank()) return null;
        try {
            long knowledgeVersion = knowledgeVersionManager.refreshCurrentVersion();
            List<String> partitions = List.of(
                    partition(PRODUCT, userId, 0L),
                    partition(BUSINESS, userId, knowledgeVersion));

            for (String partition : partitions) {
                CacheEntry exact = readValid(
                        redisTemplate.opsForValue().get(exactKey(partition, question)),
                        knowledgeVersion);
                if (exact != null) {
                    log.info("[ConsumerSemanticCache] Exact hit: scope={}", exact.scope());
                    return exact.toResponseMap();
                }
            }

            float[] queryVector = embed(question);
            if (queryVector == null || queryVector.length == 0) return null;

            List<ScoredEntry> candidates = new ArrayList<>();
            for (String partition : partitions) {
                String indexKey = indexKey(partition);
                Set<String> ids = redisTemplate.opsForZSet()
                        .reverseRange(indexKey, 0, candidateLimit - 1L);
                if (ids == null) continue;
                for (String id : ids) {
                    String json = redisTemplate.opsForValue().get(entryKey(partition, id));
                    CacheEntry candidate = readValid(json, knowledgeVersion);
                    if (candidate == null || candidate.embedding() == null
                            || candidate.embedding().size() != queryVector.length) {
                        if (json == null) redisTemplate.opsForZSet().remove(indexKey, id);
                        continue;
                    }
                    double score = cosine(queryVector, candidate.embedding());
                    candidates.add(new ScoredEntry(candidate, score));
                }
            }
            candidates.sort((left, right) -> Double.compare(right.score(), left.score()));
            if (candidates.isEmpty()) return null;
            ScoredEntry best = candidates.getFirst();
            double requiredSimilarity = PRODUCT.equals(best.entry().scope())
                    ? productSimilarityThreshold : businessSimilarityThreshold;
            if (best.score() < requiredSimilarity) {
                log.info("[ConsumerSemanticCache] Vector candidate below threshold; bypass cache: "
                                + "scope={}, similarity={}, threshold={}",
                        best.entry().scope(), format(best.score()), format(requiredSimilarity));
                return null;
            }
            if (candidates.size() > 1
                    && best.score() - candidates.get(1).score() < minimumScoreMargin) {
                log.info("[ConsumerSemanticCache] Ambiguous vector candidates; bypass cache: top1={}, top2={}",
                        format(best.score()), format(candidates.get(1).score()));
                return null;
            }
            if (equivalenceVerifier == null) return null;
            SemanticCacheEquivalenceVerifier.Verification verification = equivalenceVerifier.verify(
                    best.entry().scope(), best.entry().question(), question);
            if (!verification.accepted(verifierConfidenceThreshold)) {
                log.info("[ConsumerSemanticCache] Equivalence rejected; bypass cache: reason={}, confidence={}",
                        verification.reason(), format(verification.confidence()));
                return null;
            }
            log.info("[ConsumerSemanticCache] Verified semantic hit: scope={}, similarity={}, confidence={}",
                    best.entry().scope(), format(best.score()), format(verification.confidence()));
            return best.entry().toResponseMap();
        } catch (Exception error) {
            log.warn("[ConsumerSemanticCache] Lookup failed; continue to Router: {}", error.getMessage());
            return null;
        }
    }

    /** Stores a completed response only when Route explicitly marks it eligible. */
    public void store(long userId, String question, Map<String, Object> response) {
        if (userId <= 0 || question == null || question.isBlank() || !eligible(response)) return;
        try {
            String scope = Objects.toString(response.get("semanticCacheCategory"), "NONE");
            Duration ttl = PRODUCT.equals(scope) ? productTtl : businessTtl;
            long knowledgeVersion = BUSINESS.equals(scope)
                    ? knowledgeVersionManager.refreshCurrentVersion() : 0L;
            long now = System.currentTimeMillis();
            float[] embedded = embed(question);
            List<Float> vector = toList(embedded);
            String partition = partition(scope, userId, knowledgeVersion);
            String id = sha256(normalize(question));
            CacheEntry entry = new CacheEntry(
                    question,
                    Objects.toString(response.get("result"), ""),
                    nullableString(response.get("agentName")),
                    nullableString(response.get("intentTag")),
                    number(response.get("confidence")),
                    nullableString(response.get("executionMode")),
                    stringList(response.get("participatingAgents")),
                    scope,
                    Boolean.TRUE.equals(response.get("semanticCacheVolatileProduct")),
                    knowledgeVersion,
                    now + ttl.toMillis(),
                    vector);
            String json = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForValue().set(exactKey(partition, question), json, ttl);
            if (!vector.isEmpty()) {
                redisTemplate.opsForValue().set(entryKey(partition, id), json, ttl);
                String indexKey = indexKey(partition);
                redisTemplate.opsForZSet().add(indexKey, id, now);
                redisTemplate.expire(indexKey, ttl.plusMinutes(1));
                trimIndex(indexKey);
            }
            log.info("[ConsumerSemanticCache] Stored: scope={}, volatile={}, ttlSeconds={}, knowledgeVersion={}",
                    scope, entry.volatileProduct(), ttl.toSeconds(), knowledgeVersion);
        } catch (Exception error) {
            log.warn("[ConsumerSemanticCache] Store failed; response remains usable: {}", error.getMessage());
        }
    }

    private boolean eligible(Map<String, Object> response) {
        if (response == null || !Boolean.TRUE.equals(response.get("semanticCacheEligible"))) return false;
        String scope = Objects.toString(response.get("semanticCacheCategory"), "NONE");
        return (PRODUCT.equals(scope) || BUSINESS.equals(scope))
                && !Boolean.TRUE.equals(response.get("fromCache"))
                && !Boolean.TRUE.equals(response.get("clarification"))
                && "COMPLETED".equals(Objects.toString(response.get("workflowStatus"), ""))
                && response.get("result") != null
                && !Objects.toString(response.get("result"), "").isBlank()
                && (response.get("error") == null
                || Objects.toString(response.get("error"), "").isBlank());
    }

    private CacheEntry readValid(String json, long expectedKnowledgeVersion) {
        if (json == null || json.isBlank()) return null;
        try {
            CacheEntry entry = objectMapper.readValue(json, CacheEntry.class);
            if (entry.expiresAt() <= System.currentTimeMillis()) return null;
            if (BUSINESS.equals(entry.scope())
                    && entry.knowledgeVersion() != expectedKnowledgeVersion) return null;
            if (!PRODUCT.equals(entry.scope()) && !BUSINESS.equals(entry.scope())) return null;
            return entry;
        } catch (Exception ignored) {
            return null;
        }
    }

    private float[] embed(String question) {
        return embeddingModel != null && embeddingModel.isAvailable()
                ? embeddingModel.embedding(normalizeQuestion(question)) : null;
    }

    private void trimIndex(String indexKey) {
        Long size = redisTemplate.opsForZSet().zCard(indexKey);
        if (size != null && size > maxEntriesPerPartition) {
            redisTemplate.opsForZSet().removeRange(indexKey, 0, size - maxEntriesPerPartition - 1);
        }
    }

    private static String partition(String scope, long userId, long knowledgeVersion) {
        return scope.toLowerCase() + ":u" + userId + ":v" + knowledgeVersion;
    }

    private static String exactKey(String partition, String question) {
        return PREFIX + "exact:" + partition + ":" + sha256(normalizeQuestion(question));
    }

    private static String entryKey(String partition, String id) {
        return PREFIX + "entry:" + partition + ":" + id;
    }

    private static String indexKey(String partition) {
        return PREFIX + "index:" + partition;
    }

    private static String normalizeQuestion(String value) {
        if (value == null) return "";
        return normalize(value.replaceFirst("^\\[用户情绪:[^]]+]\\s*", ""));
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static double bounded(double value) {
        return Math.max(0d, Math.min(1d, value));
    }

    private static String format(double value) {
        return String.format("%.4f", value);
    }

    private static List<Float> toList(float[] values) {
        if (values == null || values.length == 0) return List.of();
        List<Float> result = new ArrayList<>(values.length);
        for (float value : values) result.add(value);
        return result;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Objects::nonNull).map(Objects::toString).toList();
    }

    private static String nullableString(Object value) {
        return value == null ? null : Objects.toString(value);
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static double cosine(float[] left, List<Float> right) {
        double dot = 0d;
        double leftNorm = 0d;
        double rightNorm = 0d;
        for (int i = 0; i < left.length; i++) {
            float rightValue = Objects.requireNonNullElse(right.get(i), 0f);
            dot += left[i] * rightValue;
            leftNorm += left[i] * left[i];
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm == 0d || rightNorm == 0d) return 0d;
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private record ScoredEntry(CacheEntry entry, double score) {}

    public record CacheEntry(
            String question,
            String answer,
            String agentName,
            String intentTag,
            Double confidence,
            String executionMode,
            List<String> participatingAgents,
            String scope,
            boolean volatileProduct,
            long knowledgeVersion,
            long expiresAt,
            List<Float> embedding) {

        Map<String, Object> toResponseMap() {
            Map<String, Object> result = new HashMap<>();
            result.put("result", answer);
            if (agentName != null) result.put("agentName", agentName);
            if (intentTag != null) result.put("intentTag", intentTag);
            if (confidence != null) result.put("confidence", confidence);
            result.put("executionMode", executionMode != null ? executionMode : "SINGLE_AGENT");
            result.put("participatingAgents",
                    participatingAgents != null ? participatingAgents : List.of());
            result.put("workflowStatus", "COMPLETED");
            result.put("fromCache", true);
            result.put("clarification", false);
            result.put("toolInvoked", false);
            result.put("toolUsageComplete", true);
            result.put("toolCalls", List.of());
            result.put("promptTokens", 0L);
            result.put("completionTokens", 0L);
            result.put("totalTokens", 0L);
            result.put("semanticCacheCategory", scope);
            result.put("semanticCacheVolatileProduct", volatileProduct);
            result.put("semanticCacheEligible", false);
            return result;
        }
    }
}
