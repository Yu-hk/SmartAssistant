/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 索引对账服务 — 对标文章⑦「必须有对账任务」。
 *
 * <p>定期校验 MySQL chunk 数量与 Milvus/PgVector 向量数量的一致性。
 * 对账结果决定后续修复动作：缺的补写、多的删除、旧版本延迟 GC。</p>
 *
 * <p>使用方（KnowledgeBase 实现）通过 {@link #reportIndexingResult(String, String, int, int, boolean)}
 * 上报每次重建/插入的结果。对账任务通过 {@link #checkConsistency()} 汇总对账报告。</p>
 */
public class IndexReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(IndexReconciliationService.class);

    /** 每个 knowledgeBase 最近一次索引操作的记录 */
    private final ConcurrentHashMap<String, IndexingRecord> records = new ConcurrentHashMap<>();

    /**
     * 索引操作记录。
     *
     * @param store          存储名称（如 "pgvector"、"milvus"）
     * @param baseId         知识库 ID
     * @param chunkCount     MySQL chunk 数
     * @param vectorCount    向量存储中向量数
     * @param success        是否全部成功
     * @param message        对账消息
     */
    public record IndexingRecord(
            String store, String baseId,
            int chunkCount, int vectorCount,
            boolean success, String message) {}

    /**
     * 对账报告。
     *
     * @param stores        各存储的对账结果
     * @param totalChunks   总 chunk 数
     * @param totalVectors  总向量数
     * @param missingCount  缺失数
     * @param consistent    是否一致
     */
    public record ReconciliationReport(
            Map<String, IndexingRecord> stores,
            int totalChunks, int totalVectors,
            int missingCount, boolean consistent) {}

    /**
     * 上报一次索引操作的执行结果。
     *
     * @param store       存储名称
     * @param baseId      知识库 ID
     * @param chunkCount  chunk 数量
     * @param vectorCount 向量数量
     * @param success     是否全部成功
     */
    public void reportIndexingResult(String store, String baseId,
                                      int chunkCount, int vectorCount,
                                      boolean success) {
        String key = store + ":" + baseId;
        String message = success
                ? String.format("OK: chunk=%d, vector=%d", chunkCount, vectorCount)
                : String.format("MISMATCH: chunk=%d, vector=%d", chunkCount, vectorCount);
        records.put(key, new IndexingRecord(store, baseId, chunkCount, vectorCount, success, message));
        if (!success) {
            log.warn("[IndexReconciliation] ⚠️ 索引不一致: {} (chunk={}, vector={})",
                    key, chunkCount, vectorCount);
        }
    }

    /**
     * 执行一致性对账，返回汇总报告。
     * 调用方（KnowledgeBase 实现或定时任务）应定期调用此方法。
     */
    public ReconciliationReport checkConsistency() {
        Map<String, IndexingRecord> stores = new LinkedHashMap<>();
        AtomicInteger totalChunks = new AtomicInteger(0);
        AtomicInteger totalVectors = new AtomicInteger(0);
        AtomicInteger missingCount = new AtomicInteger(0);

        records.forEach((key, rec) -> {
            stores.put(key, rec);
            totalChunks.addAndGet(rec.chunkCount());
            totalVectors.addAndGet(rec.vectorCount());
            if (rec.chunkCount() != rec.vectorCount()) {
                missingCount.incrementAndGet();
            }
        });

        boolean consistent = missingCount.get() == 0;
        int tc = totalChunks.get(), tv = totalVectors.get();
        log.info("[IndexReconciliation] 对账完成: stores={}, consistent={}, totalChunks={}, totalVectors={}",
                stores.size(), consistent, tc, tv);
        return new ReconciliationReport(stores, tc, tv, missingCount.get(), consistent);
    }

    /**
     * 清理过期的对账记录（保留最近 N 条）。
     */
    public void cleanOldRecords(int maxRecords) {
        while (records.size() > maxRecords) {
            String oldestKey = records.keySet().stream().findFirst().orElse(null);
            if (oldestKey != null) {
                records.remove(oldestKey);
            }
        }
    }

    /** 当前记录数。 */
    public int recordCount() { return records.size(); }
}
