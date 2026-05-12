package com.example.smartassistant.router.service.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量缓存存储器 — 在内存中维护问题向量索引，用于 T3 语义匹配。
 * <p>
 * 以 HashMap 存放 (question → float[]) 向量对，查找时遍历计算余弦相似度。
 * 适用于缓存场景（数据量小，TTL 自动淘汰），不依赖外部存储。
 * 当数据量超过阈值时自动清理最早写入的条目。
 */
@Component
public class VectorCacheStore {

    private static final Logger log = LoggerFactory.getLogger(VectorCacheStore.class);

    private static final int MAX_ENTRIES = 10000;
    private static final double SIMILARITY_THRESHOLD = 0.70;

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final LinkedList<String> accessOrder = new LinkedList<>();

    public record Entry(float[] vector, long cachedAt) {}

    /**
     * 存入向量
     */
    public void put(String question, float[] vector) {
        if (question == null || vector == null) return;
        if (store.size() >= MAX_ENTRIES) {
            String oldest = accessOrder.poll();
            if (oldest != null) store.remove(oldest);
        }
        store.put(question, new Entry(vector, System.currentTimeMillis()));
        accessOrder.remove(question);
        accessOrder.addLast(question);
    }

    /**
     * 查找最相似的问题
     *
     * @param queryVec 查询向量
     * @return 最相似的问题文本（相似度 ≥ threshold 时），否则 null
     */
    public String findMostSimilar(float[] queryVec) {
        if (store.isEmpty() || queryVec == null) return null;

        String bestMatch = null;
        double bestScore = 0;

        for (var entry : store.entrySet()) {
            double sim = cosineSimilarity(queryVec, entry.getValue().vector());
            if (sim > bestScore) {
                bestScore = sim;
                bestMatch = entry.getKey();
            }
        }

        return bestScore >= SIMILARITY_THRESHOLD ? bestMatch : null;
    }

    /**
     * 查找最相似的问题（返回相似度分数）
     */
    public Map.Entry<String, Double> findBestMatch(float[] queryVec) {
        if (store.isEmpty() || queryVec == null) return null;

        String bestMatch = null;
        double bestScore = 0;

        for (var entry : store.entrySet()) {
            double sim = cosineSimilarity(queryVec, entry.getValue().vector());
            if (sim > bestScore) {
                bestScore = sim;
                bestMatch = entry.getKey();
            }
        }

        return bestMatch != null && bestScore >= SIMILARITY_THRESHOLD
                ? Map.entry(bestMatch, bestScore)
                : null;
    }

    public int size() {
        return store.size();
    }

    public void clear() {
        store.clear();
        accessOrder.clear();
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }
}
