/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 反馈学习日志 — 对标文章⑥「每轮评估都会产生反馈记录」。
 *
 * <p>每轮 Agent 评估后记录结构化反馈。
 * 提供全局统计和监控接口，供管理端 API 和 Micrometer 集成。</p>
 */
public class FeedbackLog {

    private static final Logger log = LoggerFactory.getLogger(FeedbackLog.class);

    /** 最多保留的反馈条目数 */
    private static final int MAX_LOG_SIZE = 20;

    private final LinkedList<FeedbackEntry> entries = new LinkedList<>();

    /** ⭐ 全局模式计数器（跨实例共享，用于监控和管控 API） */
    private static final ConcurrentHashMap<String, AtomicLong> patternCounters = new ConcurrentHashMap<>();

    /**
     * 单条反馈记录。
     *
     * @param iteration             轮次
     * @param mistakePattern        失败模式
     * @param avoidNextTime         下次避免做法
     * @param suggestedNextStrategy 建议策略
     * @param progress              进度评估（none/low/medium/high）
     * @param shouldRecord          是否值得记录
     */
    public record FeedbackEntry(
            int iteration,
            String mistakePattern,
            String avoidNextTime,
            String suggestedNextStrategy,
            String progress,
            boolean shouldRecord) {

        public static FeedbackEntry success(int iteration, String progress) {
            return new FeedbackEntry(iteration, "", "", "", progress, false);
        }

        public static FeedbackEntry failure(int iteration, String mistakePattern,
                                             String avoidNextTime, String suggestedNextStrategy) {
            return new FeedbackEntry(iteration, mistakePattern, avoidNextTime,
                    suggestedNextStrategy, "none", true);
        }

        public static FeedbackEntry lowProgress(int iteration, String suggestedNextStrategy) {
            return new FeedbackEntry(iteration, "进展缓慢/停滞",
                    "考虑更换策略或委派子Agent",
                    suggestedNextStrategy, "low", true);
        }
    }

    /**
     * 记录一条反馈。
     */
    public void record(FeedbackEntry entry) {
        synchronized (entries) {
            if (entry.shouldRecord()) {
                entries.addLast(entry);
                if (entries.size() > MAX_LOG_SIZE) {
                    entries.removeFirst();
                }
                // ⭐ 更新全局模式计数器（监控用）
                String pattern = entry.mistakePattern();
                if (pattern != null && !pattern.isBlank()) {
                    patternCounters.computeIfAbsent(pattern, k -> new AtomicLong(0)).incrementAndGet();
                }
                log.debug("[FeedbackLog] 记录反馈: iter={}, pattern={}, strategy={}",
                        entry.iteration(), entry.mistakePattern(), entry.suggestedNextStrategy());
            }
        }
    }

    public void recordProgress(int iteration, String progress) {
        record(FeedbackEntry.success(iteration, progress));
    }

    public void recordFailure(int iteration, String mistakePattern,
                               String avoidNextTime, String suggestedNextStrategy) {
        record(FeedbackEntry.failure(iteration, mistakePattern, avoidNextTime, suggestedNextStrategy));
    }

    public void recordLowProgress(int iteration, String suggestedNextStrategy) {
        record(FeedbackEntry.lowProgress(iteration, suggestedNextStrategy));
    }

    public List<FeedbackEntry> getFeedbackHistory() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    public List<FeedbackEntry> getRecentFailures(int count) {
        synchronized (entries) {
            return entries.stream()
                    .filter(e -> e.shouldRecord())
                    .skip(Math.max(0, entries.size() - count))
                    .toList();
        }
    }

    public List<String> getTopMistakePatterns(int topN) {
        synchronized (entries) {
            return entries.stream()
                    .filter(e -> e.mistakePattern() != null && !e.mistakePattern().isBlank())
                    .map(FeedbackEntry::mistakePattern)
                    .distinct()
                    .limit(topN)
                    .toList();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ⭐ 全局统计接口（供监控/管控端点使用）
    // ═══════════════════════════════════════════════════════════════

    /** 获取全局模式计数器快照（模式名 → 累计次数）。 */
    public static ConcurrentHashMap<String, Long> getPatternCountsSnapshot() {
        ConcurrentHashMap<String, Long> snapshot = new ConcurrentHashMap<>();
        patternCounters.forEach((k, v) -> snapshot.put(k, v.get()));
        return snapshot;
    }

    /** 重置全局模式计数器（用于测试或定时清理）。 */
    public static void resetPatternCounters() {
        patternCounters.clear();
    }
}
