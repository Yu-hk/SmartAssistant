/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 * Licensed under the MIT License.
 */
package com.example.smartassistant.router.service.taskanalysis;

import com.example.smartassistant.router.model.AgentMetadata;
import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import com.example.smartassistant.router.service.cache.BgeOnnxEmbeddingService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Retrieves relevant live Agent capability declarations for task analysis.
 * Business intent types are sourced from Nacos metadata rather than Router code.
 */
@Component
public class IntentRetriever {

    private static final Logger log = LoggerFactory.getLogger(IntentRetriever.class);
    private static final double KEYWORD_WEIGHT = 0.3;

    private final BgeOnnxEmbeddingService embeddingService;
    private final AgentDiscoveryService agentDiscoveryService;
    private volatile Map<String, float[]> intentEmbeddings = Map.of();
    private volatile String embeddedCatalogFingerprint = "";

    public IntentRetriever(BgeOnnxEmbeddingService embeddingService,
                           AgentDiscoveryService agentDiscoveryService) {
        this.embeddingService = embeddingService;
        this.agentDiscoveryService = agentDiscoveryService;
    }

    @PostConstruct
    public void init() {
        ensureEmbeddings(currentIntents());
    }

    public List<IntentDef> retrieve(String question, int topK) {
        if (question == null || question.isBlank()) return List.of();
        List<IntentDef> intents = currentIntents();
        if (intents.isEmpty()) return List.of();
        int limit = Math.max(1, Math.min(topK, intents.size()));
        ensureEmbeddings(intents);
        return intentEmbeddings.isEmpty()
                ? retrieveByKeyword(question, intents, limit)
                : retrieveByVector(question, intents, limit);
    }

    public String buildIntentSection(List<IntentDef> intents) {
        if (intents == null || intents.isEmpty()) return null;
        StringBuilder section = new StringBuilder(
                "## 已注册 Agent 能力（以当前 Nacos 元数据为准）\n");
        for (IntentDef intent : intents) {
            section.append("- **").append(intent.id()).append("**（")
                    .append(intent.name()).append("）：")
                    .append(intent.description()).append('\n');
            if (!intent.examples().isBlank()) {
                section.append("  ").append(intent.examples()).append('\n');
            }
            if (!intent.relevantTools().isBlank()) {
                section.append("  ").append(intent.relevantTools()).append('\n');
            }
        }
        return section.toString();
    }

    private List<IntentDef> currentIntents() {
        Collection<DiscoveredAgent> agents = agentDiscoveryService != null
                ? agentDiscoveryService.getCachedAgents() : List.of();
        if (agents == null || agents.isEmpty()) return List.of();
        Map<String, IntentDef> definitions = new LinkedHashMap<>();
        agents.stream()
                .filter(agent -> agent != null && Boolean.TRUE.equals(agent.getHealthy()))
                .sorted(Comparator.comparing(agent -> value(agent.getServiceName())))
                .map(this::toIntentDefinition)
                .filter(intent -> intent != null && !intent.id().isBlank())
                .forEach(intent -> definitions.putIfAbsent(intent.id(), intent));
        return List.copyOf(definitions.values());
    }

    private IntentDef toIntentDefinition(DiscoveredAgent agent) {
        AgentMetadata metadata = agent.getMetadata();
        String routeName = AgentDiscoveryService.canonicalAgentName(
                firstNonBlank(agent.getAgentName(), agent.getServiceName()));
        if (routeName.isBlank()) return null;
        List<String> keywords = metadata == null ? List.of()
                : normalizedValues(metadata.getKeywordsArray());
        List<String> examples = metadata == null ? List.of()
                : normalizedValues(metadata.getRoutingExamplesArray());
        List<String> capabilities = metadata == null ? List.of()
                : normalizedValues(metadata.getCapabilitiesArray());
        String description = capabilities.isEmpty()
                ? "由服务注册中心声明的 Agent 能力"
                : String.join("、", capabilities);
        return new IntentDef(
                routeName.toUpperCase(Locale.ROOT), routeName, description, keywords,
                examples.isEmpty() ? "" : "示例：" + String.join("；", examples),
                capabilities.isEmpty() ? "" : "能力：" + String.join("、", capabilities));
    }

    private synchronized void ensureEmbeddings(List<IntentDef> intents) {
        String fingerprint = intents.stream().map(IntentRetriever::toEmbedText)
                .reduce((left, right) -> left + "\n" + right).orElse("");
        if (fingerprint.equals(embeddedCatalogFingerprint)) return;
        embeddedCatalogFingerprint = fingerprint;
        if (embeddingService == null || intents.isEmpty()) {
            intentEmbeddings = Map.of();
            return;
        }
        try {
            Map<String, float[]> refreshed = new LinkedHashMap<>();
            for (IntentDef intent : intents) {
                float[] vector = embeddingService.embed(toEmbedText(intent));
                requireUsableVector(vector, "Agent " + intent.id());
                refreshed.put(intent.id(), vector);
            }
            intentEmbeddings = Map.copyOf(refreshed);
            log.info("[IntentRetriever] 动态 Agent 能力向量已刷新: {} 个", refreshed.size());
        } catch (Exception error) {
            intentEmbeddings = Map.of();
            log.warn("[IntentRetriever] Agent 能力向量失败，降级为注册关键词匹配: {}",
                    error.getMessage());
        }
    }

    private List<IntentDef> retrieveByVector(String question, List<IntentDef> intents, int limit) {
        try {
            float[] query = embeddingService.embed(question);
            requireUsableVector(query, "用户问题");
            return intents.stream()
                    .map(intent -> new ScoredIntent(intent,
                            cosineSimilarity(query, intentEmbeddings.get(intent.id()))
                                    + KEYWORD_WEIGHT * keywordHitRate(question, intent.keywords())))
                    .sorted(Comparator.comparingDouble(ScoredIntent::score).reversed())
                    .limit(limit).map(ScoredIntent::intent).toList();
        } catch (Exception error) {
            log.warn("[IntentRetriever] 向量检索失败，降级注册关键词: {}", error.getMessage());
            return retrieveByKeyword(question, intents, limit);
        }
    }

    private static List<IntentDef> retrieveByKeyword(
            String question, List<IntentDef> intents, int limit) {
        return intents.stream()
                .map(intent -> new ScoredIntent(intent,
                        keywordHitRate(question, intent.keywords())))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredIntent::score).reversed())
                .limit(limit).map(ScoredIntent::intent).toList();
    }

    private static List<String> normalizedValues(String[] values) {
        if (values == null || values.length == 0) return List.of();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) result.add(value.trim());
        }
        return List.copyOf(result);
    }

    private static String toEmbedText(IntentDef intent) {
        return intent.id() + "：" + intent.name() + "。" + intent.description()
                + "关键词：" + String.join("、", intent.keywords());
    }

    private static double keywordHitRate(String question, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return 0;
        String normalized = question.toLowerCase(Locale.ROOT);
        long hits = keywords.stream()
                .filter(keyword -> normalized.contains(keyword.toLowerCase(Locale.ROOT)))
                .count();
        return (double) hits / keywords.size();
    }

    private static double cosineSimilarity(float[] left, float[] right) {
        requireSameDimension(left, right, "cosine");
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        double norm = Math.sqrt(leftNorm) * Math.sqrt(rightNorm);
        return norm == 0 ? 0 : dot / norm;
    }

    private static void requireUsableVector(float[] vector, String label) {
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException(label + "的嵌入向量为空");
        }
    }

    private static void requireSameDimension(float[] left, float[] right, String label) {
        requireUsableVector(left, label + " query");
        requireUsableVector(right, label + " intent");
        if (left.length != right.length) {
            throw new IllegalStateException(label + "的嵌入维度不一致");
        }
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : value(fallback);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private record ScoredIntent(IntentDef intent, double score) {
    }
}
