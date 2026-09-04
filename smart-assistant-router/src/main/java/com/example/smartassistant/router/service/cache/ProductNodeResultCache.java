/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.cache;

import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.router.model.IntentGraph.IntentNode;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import com.example.smartassistant.routing.contract.WorkflowOperation;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Short-lived Redis cache for read-only product nodes inside a routed DAG. */
@Service
public class ProductNodeResultCache {

    public static final String CACHE_HIT_KEY = "_productNodeCacheHit";
    private static final String PREFIX = "router:product-node:v1:";
    private static final Logger log = LoggerFactory.getLogger(ProductNodeResultCache.class);
    private static final Set<WorkflowOperation> ALLOWED = Set.of(
            WorkflowOperation.DISCOVER_PRODUCTS,
            WorkflowOperation.QUERY_HOT_PRODUCTS,
            WorkflowOperation.QUERY_PRODUCT,
            WorkflowOperation.ANALYZE_PRODUCT_DATA,
            WorkflowOperation.RECOMMEND_PRODUCT);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public ProductNodeResultCache(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectMapper objectMapper,
            @Value("${router.product-node-cache.ttl:PT1M}") Duration ttl) {
        this.redisTemplate = redisProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative()
                ? Duration.ofMinutes(1) : ttl;
    }

    public SubTaskResult find(IntentNode node, Long userId, Map<String, Object> resolvedInput,
                              String userProfile, Map<String, SubTaskResult> completed) {
        if (redisTemplate == null || !eligible(node) || userId == null || userId <= 0) return null;
        try {
            String json = redisTemplate.opsForValue().get(key(
                    node, userId, resolvedInput, userProfile, completed));
            if (json == null || json.isBlank()) return null;
            Entry entry = objectMapper.readValue(json, Entry.class);
            if (entry.expiresAt() <= System.currentTimeMillis()) return null;
            SubTaskResult result = new SubTaskResult(
                    node.getId(), node.getDescription(), "product", entry.answer(), true,
                    entry.realTitles(), entry.tagsByTitle());
            result.setDomainQuality(new DomainQualityResult(
                    entry.qualityStatus(), entry.qualityScore(), entry.qualityReasons()));
            Map<String, Object> data = new LinkedHashMap<>(
                    entry.structuredData() != null ? entry.structuredData() : Map.of());
            data.put(CACHE_HIT_KEY, true);
            result.setStructuredData(data);
            log.info("[ProductNodeCache] Hit: operation={}, nodeId={}",
                    node.getOperation(), node.getId());
            return result;
        } catch (Exception error) {
            log.warn("[ProductNodeCache] Lookup failed; execute product node: {}", error.getMessage());
            return null;
        }
    }

    public void store(IntentNode node, Long userId, Map<String, Object> resolvedInput,
                      String userProfile, Map<String, SubTaskResult> completed,
                      SubTaskResult result) {
        if (redisTemplate == null || !eligible(node) || userId == null || userId <= 0
                || result == null || !result.isSuccess()
                || result.getResult() == null || result.getResult().isBlank()
                || result.getDomainQuality() == null
                || result.getDomainQuality().isFail()
                || result.getDomainQuality().isUnknown()) return;
        try {
            DomainQualityResult quality = result.getDomainQuality();
            Entry entry = new Entry(
                    result.getResult(), result.getRealTitles(), result.getTagsByTitle(),
                    result.getStructuredData(), quality.getStatus(), quality.getScore(),
                    quality.getReasonCodes(), System.currentTimeMillis() + ttl.toMillis());
            redisTemplate.opsForValue().set(
                    key(node, userId, resolvedInput, userProfile, completed),
                    objectMapper.writeValueAsString(entry), ttl);
            log.info("[ProductNodeCache] Stored: operation={}, nodeId={}, ttlSeconds={}",
                    node.getOperation(), node.getId(), ttl.toSeconds());
        } catch (Exception error) {
            log.warn("[ProductNodeCache] Store failed; result remains usable: {}", error.getMessage());
        }
    }

    static boolean eligible(IntentNode node) {
        if (node == null || !"READ".equalsIgnoreCase(node.getAccessMode())
                || node.isHumanApprovalRequired()
                || !"product".equals(AgentDiscoveryService.canonicalAgentName(
                        node.getTargetAgent()))) return false;
        return WorkflowOperation.fromCode(node.getOperation()).filter(ALLOWED::contains).isPresent();
    }

    private String key(IntentNode node, Long userId, Map<String, Object> resolvedInput,
                       String userProfile, Map<String, SubTaskResult> completed) throws Exception {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("operation", node.getOperation());
        material.put("description", normalize(node.getDescription()));
        material.put("input", resolvedInput != null ? resolvedInput : Map.of());
        material.put("userProfile", userProfile != null ? userProfile : "");
        Map<String, String> predecessors = new LinkedHashMap<>();
        if (node.getDependsOn() != null && completed != null) {
            for (String dependency : node.getDependsOn()) {
                SubTaskResult value = completed.get(dependency);
                if (value != null && value.isSuccess()) {
                    predecessors.put(dependency, Objects.toString(value.getResult(), ""));
                }
            }
        }
        material.put("predecessors", predecessors);
        return PREFIX + "u" + userId + ":" + sha256(objectMapper.writeValueAsString(material));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private static String sha256(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) result.append(String.format("%02x", item));
        return result.toString();
    }

    public record Entry(
            String answer,
            List<String> realTitles,
            Map<String, String> tagsByTitle,
            Map<String, Object> structuredData,
            DomainQualityResult.Status qualityStatus,
            double qualityScore,
            List<String> qualityReasons,
            long expiresAt) {}
}
