/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 内容哈希缓存——用于 RAG 文档变更检测。
 * <p>
 * 以 baseDocId 为 key，存储规范化后的 SHA-256 哈希值。
 * 在 {@link KnowledgeIngestionService} 每次摄入前检查：
 * 如果缓存中的 hash 与新文档的 hash 相同，跳过该文档避免全量重建。
 * </p>
 *
 * <p>当前实现基于 ConcurrentHashMap（进程内缓存），适用于单实例场景。
 * 生产环境可替换为 Redis 或数据库实现，接口保持不变。</p>
 *
 * <p>参考 RAG 文章的生产原则：变更检测先粗筛（updated_at），再精判（content_hash）。
 * 此处实现第二层精判。</p>
 *
 * <pre>{@code
 * ContentHashCache cache = new ContentHashCache();
 *
 * // 摄入前检查
 * String newHash = HashUtil.normalizeAndHash(content);
 * if (!newHash.equals(cache.get(baseDocId))) {
 *     // 文档已变更，执行先删后增
 *     cache.put(baseDocId, newHash);
 * }
 * }</pre>
 */
public class ContentHashCache {

    private static final Logger log = LoggerFactory.getLogger(ContentHashCache.class);

    /** ⭐ 内容哈希命中（文档未变更而跳过摄入）计数器——零装配，复用全局注册表 */
    private static final Counter SKIP_COUNTER = Counter.builder("a2a_content_hash_skip_total")
            .description("内容哈希命中、文档未变更而跳过的摄入次数")
            .register(Metrics.globalRegistry);

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    /**
     * ⭐ chunk 级哈希缓存（P2-a）——key 为 chunk 的逻辑 baseDocId（跨版本稳定），
     * value 为该 chunk 内容的 SHA-256。用于增量摄入时只重算变更 chunk，
     * 而非整文档重算。与文档级 {@link #cache} 命名空间隔离（文档级 key 不含分块编号）。
     */
    private final ConcurrentHashMap<String, String> chunkCache = new ConcurrentHashMap<>();

    /**
     * 获取指定文档的缓存哈希。
     *
     * @param baseDocId 基础文档 ID（去除版本后缀）
     * @return 缓存的 SHA-256 哈希，从未缓存返回空字符串
     */
    public String get(String baseDocId) {
        return cache.getOrDefault(baseDocId, "");
    }

    /**
     * 更新指定文档的缓存哈希。
     *
     * @param baseDocId  基础文档 ID
     * @param contentHash 新的 SHA-256 哈希
     */
    public void put(String baseDocId, String contentHash) {
        if (baseDocId == null || baseDocId.isBlank()) return;
        if (contentHash == null) contentHash = "";
        cache.put(baseDocId, contentHash);
    }

    /**
     * 判断文档是否已变更。
     *
     * @param baseDocId   基础文档 ID
     * @param newHash     新计算的 SHA-256 哈希
     * @return true 表示文档已变更（首次摄入或内容变化）
     */
    public boolean hasChanged(String baseDocId, String newHash) {
        if (baseDocId == null || baseDocId.isBlank()) return true;
        String oldHash = cache.get(baseDocId);
        // 首次摄入（从未缓存）= 视为变更
        if (oldHash == null || oldHash.isEmpty()) return true;
        return !oldHash.equals(newHash);
    }

    /**
     * 移除指定文档的缓存（文档删除时调用）。
     */
    public void remove(String baseDocId) {
        cache.remove(baseDocId);
        chunkCache.remove(baseDocId);
    }

    // ══════════════════════════════════════════════════════
    // ⭐ chunk 级增量 diff API（P2-a）
    // ══════════════════════════════════════════════════════

    /**
     * 写入单个 chunk 的内容哈希（key 为 chunk 逻辑 baseDocId）。
     */
    public void putChunk(String chunkBaseId, String contentHash) {
        if (chunkBaseId == null || chunkBaseId.isBlank()) return;
        if (contentHash == null) contentHash = "";
        chunkCache.put(chunkBaseId, contentHash);
    }

    /**
     * 获取指定 chunk 的缓存哈希，从未缓存返回空字符串。
     */
    public String getChunkHash(String chunkBaseId) {
        return chunkCache.getOrDefault(chunkBaseId, "");
    }

    /**
     * 判断指定 chunk 是否已变更（内容哈希是否变化）。
     *
     * @param chunkBaseId chunk 逻辑 baseDocId（跨版本稳定）
     * @param newHash     新计算的 SHA-256 哈希
     * @return true 表示 chunk 已变更（首次摄入或内容变化），应重新摄入
     */
    public boolean hasChunkChanged(String chunkBaseId, String newHash) {
        if (chunkBaseId == null || chunkBaseId.isBlank()) return true;
        String oldHash = chunkCache.get(chunkBaseId);
        if (oldHash == null || oldHash.isEmpty()) return true;
        return !oldHash.equals(newHash);
    }

    /**
     * 批量 diff：给定一批 chunk 的 (逻辑 baseDocId → 内容哈希)，
     * 返回其中真正变更的 chunk（仅这些需要重新向量化与入库）。
     *
     * @param newChunkHashes 本次摄入的各 chunk 哈希
     * @return 变更 chunk 的 baseDocId → 新哈希（有序）
     */
    public java.util.Map<String, String> diffChunks(java.util.Map<String, String> newChunkHashes) {
        java.util.Map<String, String> changed = new java.util.LinkedHashMap<>();
        if (newChunkHashes == null) return changed;
        for (java.util.Map.Entry<String, String> e : newChunkHashes.entrySet()) {
            if (hasChunkChanged(e.getKey(), e.getValue())) {
                changed.put(e.getKey(), e.getValue());
            }
        }
        return changed;
    }

    /** 当前缓存条目数 */
    public int size() {
        return cache.size();
    }

    /** 清空全部缓存 */
    public void clear() {
        cache.clear();
        chunkCache.clear();
        log.info("[ContentHashCache] 缓存已清空");
    }

    /**
     * 判断指定文档是否需要重新摄入。
     * <p>
     * 与 {@link #hasChanged(String, String)} 不同，
     * 此方法在「未变更」时还会记录一条 debug 日志。
     * </p>
     *
     * @param baseDocId   基础文档 ID
     * @param newHash     新计算的 SHA-256 哈希
     * @return true 需要重新摄入（变更或首次）
     */
    public boolean needsReingest(String baseDocId, String newHash) {
        boolean changed = hasChanged(baseDocId, newHash);
        if (!changed) {
            SKIP_COUNTER.increment();
            log.debug("[ContentHashCache] 文档未变更，跳过: baseDocId={}", baseDocId);
        }
        return changed;
    }
}
