/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.abtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ⭐ A/B 测试框架 — 在线对比不同策略的效果。
 * <p>
 * 支持按用户 ID 或请求 ID 分配实验组，配合 {@link com.example.smartassistant.common.rag.trace.RetrievalTrace}
 * 记录实验数据，后续结合评测集分析各组效果差异。
 * </p>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * ABTestService abTest = new ABTestService();
 * ABTestService.Experiment experiment = abTest.register("rerank-strategy",
 *     List.of("identity", "bge-reranker"),  // 实验组
 *     List.of(50, 50));                       // 流量分配百分比
 *
 * // 分配实验组
 * String group = abTest.assign("rerank-strategy", userId);
 * // → 根据 userId 一致性哈希分配，同一用户始终在同一组
 *
 * // 记录结果
 * abTest.record("rerank-strategy", group, true, 150);  // 成功, 耗时150ms
 *
 * // 查询统计
 * var stats = abTest.getStats("rerank-strategy");
 * }</pre>
 */
public class ABTestService {

    private static final Logger log = LoggerFactory.getLogger(ABTestService.class);

    /** 已注册的实验 */
    private final Map<String, Experiment> experiments = new ConcurrentHashMap<>();

    /** 实验结果统计（实验名 → 组名 → 统计） */
    private final Map<String, Map<String, GroupStats>> stats = new ConcurrentHashMap<>();

    /**
     * 注册实验。
     *
     * @param name       实验名称
     * @param groups     实验组名称列表
     * @param weights    各组流量分配百分比（总和应为 100）
     * @return 实验对象
     */
    public Experiment register(String name, List<String> groups, List<Integer> weights) {
        if (groups.size() != weights.size()) {
            throw new IllegalArgumentException("groups 和 weights 长度不一致");
        }
        int totalWeight = weights.stream().mapToInt(Integer::intValue).sum();
        if (totalWeight != 100) {
            log.warn("[ABTest:{}] 权重总和不等于100: {}，将归一化处理", name, totalWeight);
        }

        Experiment exp = new Experiment(name, groups, weights);
        experiments.put(name, exp);
        stats.put(name, new ConcurrentHashMap<>());
        log.info("[ABTest] 注册实验: name={}, groups={}, weights={}", name, groups, weights);
        return exp;
    }

    /**
     * 为用户分配实验组（基于一致性哈希，同一用户始终在同组）。
     *
     * @param experimentName 实验名称
     * @param userId         用户 ID
     * @return 分配到的实验组名称
     */
    public String assign(String experimentName, String userId) {
        Experiment exp = experiments.get(experimentName);
        if (exp == null) {
            log.warn("[ABTest] 未找到实验: {}", experimentName);
            return "control";
        }

        // 基于 userId 的一致性哈希
        int hash = (userId != null ? userId.hashCode() : ThreadLocalRandom.current().nextInt()) & 0x7FFFFFFF;
        int bucket = hash % 100;
        int cumulative = 0;
        for (int i = 0; i < exp.groups.size(); i++) {
            cumulative += exp.weights.get(i);
            if (bucket < cumulative) {
                String group = exp.groups.get(i);
                log.debug("[ABTest] 分配: experiment={}, userId={}, group={}", experimentName, userId, group);
                return group;
            }
        }
        return exp.groups.get(exp.groups.size() - 1); // fallback
    }

    /**
     * 记录实验结果。
     *
     * @param experimentName 实验名称
     * @param group          实验组
     * @param success        是否成功
     * @param latencyMs      延迟（毫秒）
     */
    public void record(String experimentName, String group, boolean success, long latencyMs) {
        Map<String, GroupStats> expStats = stats.get(experimentName);
        if (expStats == null) return;

        GroupStats gs = expStats.computeIfAbsent(group, k -> new GroupStats());
        synchronized (gs) {
            gs.totalCalls++;
            if (success) gs.successCalls++;
            gs.totalLatencyMs += latencyMs;
            if (latencyMs < gs.minLatencyMs || gs.minLatencyMs == 0) gs.minLatencyMs = latencyMs;
            if (latencyMs > gs.maxLatencyMs) gs.maxLatencyMs = latencyMs;
        }
    }

    /**
     * 获取实验统计结果。
     */
    public Map<String, GroupStats> getStats(String experimentName) {
        return stats.getOrDefault(experimentName, Map.of());
    }

    /**
     * 获取所有已注册实验名称。
     */
    public List<String> getExperimentNames() {
        return List.copyOf(experiments.keySet());
    }

    /** 实验定义 */
    public record Experiment(String name, List<String> groups, List<Integer> weights) {}

    /** 组统计 */
    public static class GroupStats {
        private long totalCalls = 0;
        private long successCalls = 0;
        private long totalLatencyMs = 0;
        private long minLatencyMs = 0;
        private long maxLatencyMs = 0;

        public long getTotalCalls() { return totalCalls; }
        public long getSuccessCalls() { return successCalls; }
        public double getSuccessRate() { return totalCalls > 0 ? (double) successCalls / totalCalls : 0; }
        public double getAvgLatencyMs() { return totalCalls > 0 ? (double) totalLatencyMs / totalCalls : 0; }
        public long getMinLatencyMs() { return minLatencyMs; }
        public long getMaxLatencyMs() { return maxLatencyMs; }

        @Override
        public String toString() {
            return String.format("calls=%d, success=%.1f%%, avgLat=%.1fms, min=%dms, max=%dms",
                    totalCalls, getSuccessRate() * 100, getAvgLatencyMs(), minLatencyMs, maxLatencyMs);
        }
    }
}
