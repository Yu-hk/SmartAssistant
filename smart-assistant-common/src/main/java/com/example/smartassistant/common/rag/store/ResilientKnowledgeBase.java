/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.store;

import com.example.smartassistant.common.rag.AclContext;
import com.example.smartassistant.common.rag.DocumentStatus;
import com.example.smartassistant.common.rag.KnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.KnowledgeHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 弹性知识库装饰器——在 {@link KnowledgeBase} 主库（PG）与降级内存库之间做请求级故障转移。
 * <p>
 * 设计目标（REQ-2 / REQ-4 / 红线"PgVector 不可用时检索不中断"）：
 * <ul>
 *   <li><b>写入</b>：优先写主库；主库成功则顺带镜像到内存快照（保证降级可读）；
 *       主库失败则退而写入内存快照，避免数据丢失（best-effort）。</li>
 *   <li><b>读取</b>：优先查主库；主库异常或已连续失败达到阈值则透明降级到内存快照。</li>
 *   <li><b>三态</b>：{@code pg/memory/auto} 由装配方（{@code RagProductionAutoConfiguration}）决定
 *       主/备组合；本类只负责运行时的请求级降级，不感知模式。</li>
 * </ul>
 *
 * <p>降级触发：{@link #recordFailure()} 在连续失败达到 {@code healthFailThreshold} 次后
 * 置 {@code forceDegraded=true}，后续请求直接走内存快照，直到一次主库成功 {@link #resetHealth()}。</p>
 */
public class ResilientKnowledgeBase implements KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(ResilientKnowledgeBase.class);

    private final String name;
    private final KnowledgeBase primary;
    private final KnowledgeBase fallback;

    /** 运行时按请求降级开关 */
    private final boolean degradeEnabled;
    /** 连续失败次数阈值，达到即整体降级 */
    private final int healthFailThreshold;

    private final AtomicInteger failureStreak = new AtomicInteger(0);
    private volatile boolean forceDegraded = false;

    public ResilientKnowledgeBase(String name, KnowledgeBase primary, KnowledgeBase fallback) {
        this(name, primary, fallback, true, 3);
    }

    public ResilientKnowledgeBase(String name, KnowledgeBase primary, KnowledgeBase fallback,
                                  boolean degradeEnabled, int healthFailThreshold) {
        this.name = name;
        this.primary = primary;
        this.fallback = fallback;
        this.degradeEnabled = degradeEnabled;
        this.healthFailThreshold = Math.max(1, healthFailThreshold);
    }

    @Override
    public String getName() { return name; }

    @Override
    public void addDocument(KnowledgeDocument doc) {
        if (doc == null) return;
        try {
            primary.addDocument(doc);
            // 主库成功：重置健康计数，并镜像到内存快照（保持降级可读）
            failureStreak.set(0);
            forceDegraded = false;
            if (fallback != null) {
                safe(() -> fallback.addDocument(doc), "镜像到内存快照");
            }
        } catch (Exception e) {
            log.warn("[ResilientKB:{}] 主库写入失败，降级写内存快照: {}", name, e.getMessage());
            recordFailure();
            if (fallback != null) {
                fallback.addDocument(doc);
            }
        }
    }

    @Override
    public void removeDocument(String id) {
        safe(() -> primary.removeDocument(id), "主库删除");
        if (fallback != null) safe(() -> fallback.removeDocument(id), "内存删除");
    }

    @Override
    public void removeByBaseDocId(String baseDocId) {
        safe(() -> primary.removeByBaseDocId(baseDocId), "主库按base删除");
        if (fallback != null) safe(() -> fallback.removeByBaseDocId(baseDocId), "内存按base删除");
    }

    @Override
    public List<String> listIdsByBaseDocId(String baseDocId) {
        if (!forceDegraded && primary != null) {
            try {
                return primary.listIdsByBaseDocId(baseDocId);
            } catch (Exception e) {
                log.warn("[ResilientKB:{}] 主库 listIdsByBaseDocId 失败，降级: {}", name, e.getMessage());
                recordFailure();
            }
        }
        return fallback != null ? fallback.listIdsByBaseDocId(baseDocId) : List.of();
    }

    @Override
    public void updateStatus(String docId, DocumentStatus status) {
        safe(() -> primary.updateStatus(docId, status), "主库更新状态");
        if (fallback != null) safe(() -> fallback.updateStatus(docId, status), "内存更新状态");
    }

    @Override
    public List<KnowledgeHit> search(String query, int topK, AclContext acl) {
        if (!forceDegraded && primary != null) {
            try {
                List<KnowledgeHit> hits = primary.search(query, topK, acl);
                failureStreak.set(0);
                forceDegraded = false;
                return hits;
            } catch (Exception e) {
                log.warn("[ResilientKB:{}] 主库检索失败，降级内存快照: {}", name, e.getMessage());
                recordFailure();
            }
        }
        if (fallback != null) {
            return fallback.search(query, topK, acl);
        }
        // 无降级库：返回空以避免中断调用方（检索失败应优雅降级，而非抛异常）
        log.warn("[ResilientKB:{}] 主库不可用且无降级库，返回空结果", name);
        return List.of();
    }

    @Override
    public List<KnowledgeHit> search(String query, int topK, String tenantId) {
        if (!forceDegraded && primary != null) {
            try {
                List<KnowledgeHit> hits = primary.search(query, topK, tenantId);
                failureStreak.set(0);
                forceDegraded = false;
                return hits;
            } catch (Exception e) {
                log.warn("[ResilientKB:{}] 主库检索(租户)失败，降级内存快照: {}", name, e.getMessage());
                recordFailure();
            }
        }
        if (fallback != null) {
            return fallback.search(query, topK, tenantId);
        }
        log.warn("[ResilientKB:{}] 主库不可用且无降级库，返回空结果", name);
        return List.of();
    }

    @Override
    public int size() {
        if (!forceDegraded && primary != null) {
            try {
                return primary.size();
            } catch (Exception e) {
                log.warn("[ResilientKB:{}] 主库 size 失败，降级: {}", name, e.getMessage());
                recordFailure();
            }
        }
        return fallback != null ? fallback.size() : 0;
    }

    @Override
    public void reindex() {
        safe(() -> { if (primary != null) primary.reindex(); }, "主库 reindex");
        if (fallback != null) safe(() -> fallback.reindex(), "内存 reindex");
    }

    @Override
    public List<KnowledgeDocument> listAll() {
        if (!forceDegraded && primary != null) {
            try {
                return primary.listAll();
            } catch (Exception e) {
                log.warn("[ResilientKB:{}] 主库 listAll 失败，降级: {}", name, e.getMessage());
                recordFailure();
            }
        }
        return fallback != null ? fallback.listAll() : List.of();
    }

    // ==================== 健康 / 降级 ====================

    /** 记录一次主库失败，达到阈值则进入强制降级 */
    private void recordFailure() {
        int streak = failureStreak.incrementAndGet();
        if (degradeEnabled && streak >= healthFailThreshold) {
            forceDegraded = true;
            log.warn("[ResilientKB:{}] 主库连续失败 {} 次，进入强制降级（只读内存快照）", name, streak);
        }
    }

    /** 当前是否处于强制降级态（供健康检查/监控读取） */
    public boolean isDegraded() { return forceDegraded; }

    /** 重置健康状态（主库恢复后调用） */
    public void resetHealth() {
        failureStreak.set(0);
        forceDegraded = false;
    }

    private void safe(Runnable action, String desc) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("[ResilientKB:{}] {} 异常（已忽略）: {}", name, desc, e.getMessage());
        }
    }
}
