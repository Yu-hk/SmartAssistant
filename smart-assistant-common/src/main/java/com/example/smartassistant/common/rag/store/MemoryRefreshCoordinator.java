/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.store;

import com.example.smartassistant.common.rag.InMemoryKnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 内存快照刷新协调器（REQ-4 多实例读共享一致性的内存侧保障）。
 * <p>
 * 在 {@code auto/pg} 模式下，PG 是统一写入与跨实例共享的真相源；本协调器周期性地
 * 把 PG 主库的全量文档拉取并 {@link InMemoryKnowledgeBase#replaceAll(java.util.Collection) 替换}
 * 进内存降级快照，使 {@link ResilientKnowledgeBase} 在 PG 偶发不可用时，其内存快照仍然
 * 持有"最近一次健康时刻"的数据，避免降级即"无数据"。
 * </p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>容错</b>：PG 不可用时 {@link #refresh()} 静默失败并记录日志，不影响调度；</li>
 *   <li><b>兜底</b>：每次 {@link #start()} 先执行一次即时刷新，保证启动即有快照；</li>
 *   <li><b>可关</b>：{@code mode=memory} 时由装配方决定是否启动（无 PG 主库则不启动）。</li>
 * </ul>
 */
public class MemoryRefreshCoordinator {

    private static final Logger log = LoggerFactory.getLogger(MemoryRefreshCoordinator.class);

    private final InMemoryKnowledgeBase fallback;
    private final KnowledgeBase primary;
    private final long intervalMs;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mem-refresh-coordinator");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean running = false;

    public MemoryRefreshCoordinator(InMemoryKnowledgeBase fallback, KnowledgeBase primary, long intervalMs) {
        this.fallback = fallback;
        this.primary = primary;
        this.intervalMs = intervalMs > 0 ? intervalMs : 5000;
    }

    /** 启动周期性刷新（先执行一次即时刷新） */
    public synchronized void start() {
        if (running) return;
        if (primary == null || fallback == null) {
            log.info("[MemRefresh] 未配置主库/快照，跳过启动");
            return;
        }
        running = true;
        refresh(); // 启动即时刷新
        scheduler.scheduleWithFixedDelay(this::refresh, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("[MemRefresh] 已启动：间隔 {}ms", intervalMs);
    }

    /** 停止刷新调度 */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        scheduler.shutdownNow();
        log.info("[MemRefresh] 已停止");
    }

    /**
     * 立即从主库拉取全量并刷新内存快照（best-effort）。
     * <p>PG 异常时静默返回，保留现有快照。</p>
     */
    public void refresh() {
        if (primary == null || fallback == null) return;
        try {
            List<KnowledgeDocument> all = primary.listAll();
            fallback.replaceAll(all);
            log.debug("[MemRefresh] 快照刷新：{} 篇文档", all.size());
        } catch (Exception e) {
            log.warn("[MemRefresh] 刷新失败（保留现有快照）: {}", e.getMessage());
        }
    }

    /** 当前是否处于运行态 */
    public boolean isRunning() { return running; }
}
