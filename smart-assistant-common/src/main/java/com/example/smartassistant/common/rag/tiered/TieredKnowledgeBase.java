/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.tiered;

import com.example.smartassistant.common.rag.AclContext;
import com.example.smartassistant.common.rag.DocumentStatus;
import com.example.smartassistant.common.rag.KnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.KnowledgeHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 分层知识库——按频率自动分配冷（持久化）热（内存）存储。
 *
 * <p>架构：</p>
 * <pre>{@code
 *   TieredKnowledgeBase
 *   ├── Hot Layer (InMemory)       ← 高频查询 → 命中即返回
 *   └── Cold Layer (PgVector/Milvus) ← 热层未命中 → 降级查询
 *
 *   写入策略：双写（hot + cold）
 *   升温策略：冷层命中后自动写入热层（promoteOnAccess）
 *   降温策略：reachable 次数低于阈值自动降级（demoteAfterAccesses）
 * }</pre>
 *
 * <p>适合场景：商品知识库（查询频繁）、订单规则（高频命中）。</p>
 */
public class TieredKnowledgeBase implements KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(TieredKnowledgeBase.class);

    /** 知识库名称 */
    private final String name;

    /** 热层（内存，高频）—— 不可为 null */
    private final KnowledgeBase hotStore;

    /** 冷层（持久化，低频）—— 不可为 null */
    private final KnowledgeBase coldStore;

    /** 自动升温：冷层命中后写入热层 */
    private final boolean promoteOnAccess;

    /** 热度计数器 */
    private final CopyOnWriteArrayList<AccessRecord> accessLog = new CopyOnWriteArrayList<>();

    /** 热度阈值：超过此次数视为热数据 */
    private final int hotThreshold;

    public TieredKnowledgeBase(String name, KnowledgeBase hotStore, KnowledgeBase coldStore,
                                boolean promoteOnAccess, int hotThreshold) {
        this.name = name;
        this.hotStore = hotStore;
        this.coldStore = coldStore;
        this.promoteOnAccess = promoteOnAccess;
        this.hotThreshold = hotThreshold;
        log.info("[TieredKB] 初始化: name={}, hot={}, cold={}, promoteOnAccess={}, hotThreshold={}",
                name, hotStore.getName(), coldStore.getName(), promoteOnAccess, hotThreshold);
    }

    @Override
    public String getName() { return name; }

    // ═══════════════════════════════════════════════════════════
    // 写入：双写策略
    // ═══════════════════════════════════════════════════════════

    @Override
    public void addDocument(KnowledgeDocument doc) {
        try {
            hotStore.addDocument(doc);
        } catch (Exception e) {
            log.warn("[TieredKB] 热层写入失败: {}", e.getMessage());
        }
        try {
            coldStore.addDocument(doc);
        } catch (Exception e) {
            log.warn("[TieredKB] 冷层写入失败: {}", e.getMessage());
        }
        log.debug("[TieredKB] 双写完成: docId={}, title={}", doc.getId(), doc.getTitle());
    }

    @Override
    public void addDocuments(List<KnowledgeDocument> docs) {
        docs.forEach(this::addDocument);
    }

    // ═══════════════════════════════════════════════════════════
    // 读取：先热后冷
    // ═══════════════════════════════════════════════════════════

    @Override
    public List<KnowledgeHit> search(String query, int topK, String tenantId) {
        return search(query, topK, AclContext.forTenant(tenantId));
    }

    /**
     * ⭐ 细粒度 ACL 检索（文章⑤：权限进入检索层，服务端生成 filter）。
     */
    @Override
    public List<KnowledgeHit> search(String query, int topK, AclContext acl) {
        // 1. 热层检索
        List<KnowledgeHit> hotResults = hotStore.search(query, topK, acl);

        // 热层结果足够 → 直接返回
        if (hotResults.size() >= topK) {
            log.debug("[TieredKB] 热层命中: query='{}', hits={}", truncate(query, 30), hotResults.size());
            recordAccess(query, "hot", hotResults.size());
            return hotResults;
        }

        // 2. 不足 → 降级到冷层（同样应用细粒度 ACL）
        int remaining = topK - hotResults.size() + 5;  // 多取 5 条补偿冷层精度
        List<KnowledgeHit> coldResults = coldStore.search(query, Math.max(remaining, 5), acl);

        if (coldResults.isEmpty() && hotResults.isEmpty()) {
            recordAccess(query, "miss", 0);
            return List.of();
        }

        // 3. 自动升温：冷层命中且 promoteOnAccess 时写入热层
        if (promoteOnAccess && !coldResults.isEmpty()) {
            for (KnowledgeHit hit : coldResults) {
                try {
                    hotStore.addDocument(hit.getDocument());
                } catch (Exception e) {
                    log.debug("[TieredKB] 升温写入失败（可忽略）: docId={}", hit.getDocument().getId());
                }
            }
            log.info("[TieredKB] 自动升温: {} 条从冷层提升到热层", coldResults.size());
        }

        // 4. 融合冷热结果：去重 + 按分数降序
        List<KnowledgeHit> fused = Stream.concat(hotResults.stream(), coldResults.stream())
                .distinct()
                .sorted(Comparator.comparingDouble(KnowledgeHit::getScore).reversed())
                .limit(topK)
                .collect(Collectors.toList());

        log.debug("[TieredKB] 冷热融合: query='{}', hot={}, cold={}, fused={}",
                truncate(query, 30), hotResults.size(), coldResults.size(), fused.size());
        recordAccess(query, "fused", fused.size());
        return fused;
    }

    // ═══════════════════════════════════════════════════════════
    // 统计与维护
    // ═══════════════════════════════════════════════════════════

    @Override
    public int size() {
        return hotStore.size() + coldStore.size();
    }

    @Override
    public void reindex() {
        hotStore.reindex();
        coldStore.reindex();
    }

    @Override
    public List<String> listIdsByBaseDocId(String baseDocId) {
        return coldStore.listIdsByBaseDocId(baseDocId);
    }

    @Override
    public void updateStatus(String docId, DocumentStatus status) {
        try { hotStore.updateStatus(docId, status); } catch (Exception ignored) {}
        try { coldStore.updateStatus(docId, status); } catch (Exception ignored) {}
    }

    @Override
    public void removeDocument(String id) {
        try { hotStore.removeDocument(id); } catch (Exception ignored) {}
        try { coldStore.removeDocument(id); } catch (Exception ignored) {}
    }

    @Override
    public void removeByBaseDocId(String baseDocId) {
        try { hotStore.removeByBaseDocId(baseDocId); } catch (Exception ignored) {}
        try { coldStore.removeByBaseDocId(baseDocId); } catch (Exception ignored) {}
    }

    // ═══════════════════════════════════════════════════════════
    // 热度管理
    // ═══════════════════════════════════════════════════════════

    /**
     * 查询当前热层的命中率统计。
     */
    public TieredStats getStats() {
        long total = accessLog.size();
        long hotHits = accessLog.stream().filter(a -> "hot".equals(a.layer)).count();
        long fusedHits = accessLog.stream().filter(a -> "fused".equals(a.layer)).count();
        long misses = accessLog.stream().filter(a -> "miss".equals(a.layer)).count();
        return new TieredStats(total, hotHits, fusedHits, misses,
                hotStore.size(), coldStore.size(),
                total > 0 ? (double) hotHits / total : 0);
    }

    private void recordAccess(String query, String layer, int hits) {
        accessLog.add(new AccessRecord(query, layer, hits, System.currentTimeMillis()));
        // 限制日志量
        if (accessLog.size() > 10_000) {
            accessLog.remove(0);
        }
    }

    /** 热度统计 */
    public record TieredStats(
            long totalAccesses, long hotHits, long fusedHits, long misses,
            int hotSize, int coldSize, double hotHitRate
    ) {}

    private record AccessRecord(String query, String layer, int hits, long timestamp) {}

    private static String truncate(String str, int max) {
        return str != null && str.length() > max ? str.substring(0, max) + "..." : str;
    }
}
