/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.audit;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * AI 审计事件存储 — 内存环形缓冲区，承载每次生产 AI 调用的结构化审计记录。
 *
 * <p>设计取舍：审计落库优先保证零外部依赖、可随应用启动即用；采用有界环形缓冲
 * （默认保留最近 1000 条）避免内存无限增长。后续如需跨重启持久化，可替换为
 * Redis / 数据库实现，仅需实现同等 {@code add/recent/all} 接口。</p>
 *
 * <p>由 {@code AdvisorChainAutoConfiguration#aiAuditStore()} 以 {@code @Bean} 注册，
 * 并由其 {@code @EventListener(AiAuditEvent.class)} 接收事件写入。</p>
 */
public class AiAuditStore {

    private final int capacity;
    private final ConcurrentLinkedDeque<AiAuditEvent> events = new ConcurrentLinkedDeque<>();

    public AiAuditStore() {
        this(1000);
    }

    public AiAuditStore(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** 写入一条审计事件；缓冲超过容量时淘汰最旧记录 */
    public void add(AiAuditEvent event) {
        if (event == null) return;
        events.addLast(event);
        while (events.size() > capacity) {
            events.pollFirst();
        }
    }

    /** 返回最近 n 条事件（按时间升序）；n<=0 时返回全部 */
    public List<AiAuditEvent> recent(int n) {
        if (n <= 0) return List.copyOf(events);
        int skip = Math.max(0, events.size() - n);
        return events.stream().skip(skip).toList();
    }

    /** 返回全部事件（按时间升序） */
    public List<AiAuditEvent> all() {
        return List.copyOf(events);
    }

    /** 当前缓冲中事件总数 */
    public int size() {
        return events.size();
    }
}
