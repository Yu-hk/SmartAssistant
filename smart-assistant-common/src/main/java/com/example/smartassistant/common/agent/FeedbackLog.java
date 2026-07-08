/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/**
 * 反馈学习日志 — 对标文章⑥「每轮评估都会产生反馈记录」。
 *
 * <p>每轮 Agent 评估后记录结构化反馈：</p>
 * <ul>
 *   <li><b>mistakePattern</b>：本次失败的模式（如"重复调用"、"空返回"、"参数错误"）</li>
 *   <li><b>avoidNextTime</b>：下次应避免什么（如"不要连续调用同一个无变化工具"）</li>
 *   <li><b>suggestedNextStrategy</b>：建议的下一步策略</li>
 * </ul>
 *
 * <p>最多保留 {@link #MAX_LOG_SIZE} 条，超过丢弃最早的。
 * 通过 {@link #getFeedbackHistory()} 供会话经验审查使用。</p>
 */
public class FeedbackLog {

    private static final Logger log = LoggerFactory.getLogger(FeedbackLog.class);

    /** 最多保留的反馈条目数 */
    private static final int MAX_LOG_SIZE = 20;

    private final LinkedList<FeedbackEntry> entries = new LinkedList<>();

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

        /** 创建一条"无失败"的正常记录。 */
        public static FeedbackEntry success(int iteration, String progress) {
            return new FeedbackEntry(iteration, "", "", "", progress, false);
        }

        /** 创建一条失败反馈记录。 */
        public static FeedbackEntry failure(int iteration, String mistakePattern,
                                             String avoidNextTime, String suggestedNextStrategy) {
            return new FeedbackEntry(iteration, mistakePattern, avoidNextTime,
                    suggestedNextStrategy, "none", true);
        }

        /** 创建一条低进展告警记录。 */
        public static FeedbackEntry lowProgress(int iteration, String suggestedNextStrategy) {
            return new FeedbackEntry(iteration, "进展缓慢/停滞",
                    "考虑更换策略或委派子Agent",
                    suggestedNextStrategy, "low", true);
        }
    }

    /**
     * 记录一条反馈。
     *
     * @param entry 反馈条目
     */
    public void record(FeedbackEntry entry) {
        synchronized (entries) {
            if (entry.shouldRecord()) {
                entries.addLast(entry);
                if (entries.size() > MAX_LOG_SIZE) {
                    entries.removeFirst();
                }
                log.debug("[FeedbackLog] 记录反馈: iter={}, pattern={}, strategy={}",
                        entry.iteration(), entry.mistakePattern(), entry.suggestedNextStrategy());
            }
        }
    }

    /**
     * 记录"本轮一切正常"的进展标记。
     */
    public void recordProgress(int iteration, String progress) {
        record(FeedbackEntry.success(iteration, progress));
    }

    /**
     * 记录一次失败。
     */
    public void recordFailure(int iteration, String mistakePattern,
                               String avoidNextTime, String suggestedNextStrategy) {
        record(FeedbackEntry.failure(iteration, mistakePattern, avoidNextTime, suggestedNextStrategy));
    }

    /**
     * 记录低进展告警。
     */
    public void recordLowProgress(int iteration, String suggestedNextStrategy) {
        record(FeedbackEntry.lowProgress(iteration, suggestedNextStrategy));
    }

    /**
     * 获取当前所有反馈记录（最近最多 20 条，最早的在前面）。
     */
    public List<FeedbackEntry> getFeedbackHistory() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    /**
     * 获取最近 N 条失败反馈记录。
     */
    public List<FeedbackEntry> getRecentFailures(int count) {
        synchronized (entries) {
            return entries.stream()
                    .filter(e -> e.shouldRecord())
                    .skip(Math.max(0, entries.size() - count))
                    .toList();
        }
    }

    /**
     * 获取最常见的失败模式（用于经验审查）。
     */
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
}
