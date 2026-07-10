/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * ⭐ G4 运营看板指标收集器（对标《生产级 Agent 架构实战》"每天看 6 指标"）。
 *
 * <p>统一采集生产级 Agent 系统最关键的 6 个运营指标，供 Grafana 看板固化展示：
 * <ol>
 *   <li>{@code a2a_route_latency_seconds} — 端到端路由/响应延迟（Timer）</li>
 *   <li>{@code a2a_tool_calls_total{outcome}} — 工具调用总量与失败率（Counter）</li>
 *   <li>{@code a2a_turn_tokens} — 单轮 Token 消耗（DistributionSummary）</li>
 *   <li>{@code a2a_answers_total} / {@code a2a_answers_no_evidence_total} — 应答量与无证据拒答率（Counter）</li>
 *   <li>{@code a2a_handoffs_total{reason}} — 人工/专业接管率（Counter）</li>
 *   <li>{@code a2a_semantic_cache_hits_total} / {@code a2a_semantic_cache_misses_total} — 语义缓存命中率（Counter）</li>
 * </ol>
 *
 * <h3>设计</h3>
 * 直接复用 Micrometer 全局注册表 {@link Metrics#globalRegistry}（Spring Boot 已将其主注册表
 * Prometheus 自动挂载到全局），<b>无需任何 Spring 装配、无需构造函数改动</b>，对各消费类的
 * 既有测试（大量 {@code new XxxAgent(...)} 手动构造）零侵入。全局注册表在非生产/无注册表环境
 * 下为 no-op，调用安全。
 */
public class OpsMetrics {

    private static final String AGENT_UNKNOWN = "unknown";

    /** 记录端到端路由/响应延迟（毫秒）。 */
    public void recordRouteLatency(String agent, String intent, long milliseconds) {
        Timer.builder("a2a_route_latency_seconds")
                .description("端到端路由/响应延迟（秒）")
                .tag("agent", sanitize(agent))
                .tag("intent", sanitize(intent))
                .register(Metrics.globalRegistry)
                .record(milliseconds, TimeUnit.MILLISECONDS);
    }

    /** 记录一次工具调用结果（成功/失败）。 */
    public void recordToolCall(String agent, boolean success) {
        Counter.builder("a2a_tool_calls_total")
                .description("工具调用总量（按结果分成功/失败）")
                .tag("agent", sanitize(agent))
                .tag("outcome", success ? "success" : "failure")
                .register(Metrics.globalRegistry)
                .increment();
    }

    /** 记录单轮 Token 消耗（仅正值计入）。 */
    public void recordTurnTokens(String agent, long totalTokens) {
        if (totalTokens <= 0) return;
        DistributionSummary.builder("a2a_turn_tokens")
                .description("单轮对话 Token 消耗（总量分布）")
                .tag("agent", sanitize(agent))
                .register(Metrics.globalRegistry)
                .record(totalTokens);
    }

    /** 记录一次有效应答（无证据拒答之外的正常/兜底应答），作为"无答案率"分母。 */
    public void recordAnswer(String agent, String intent) {
        Counter.builder("a2a_answers_total")
                .description("Agent 有效应答总量")
                .tag("agent", sanitize(agent))
                .tag("intent", sanitize(intent))
                .register(Metrics.globalRegistry)
                .increment();
    }

    /** 记录一次无证据拒答（检索质量不足，生成前短路）。 */
    public void recordNoEvidenceAnswer(String agent, String intent) {
        Counter.builder("a2a_answers_no_evidence_total")
                .description("无证据拒答总量（检索不足，未调用生成）")
                .tag("agent", sanitize(agent))
                .tag("intent", sanitize(intent))
                .register(Metrics.globalRegistry)
                .increment();
    }

    /** 记录一次人工/专业接管（如重度情绪风险安全兜底）。 */
    public void recordHandoff(String reason, String agent) {
        Counter.builder("a2a_handoffs_total")
                .description("人工/专业接管总量（按原因分）")
                .tag("reason", sanitize(reason))
                .tag("agent", sanitize(agent))
                .register(Metrics.globalRegistry)
                .increment();
    }

    /** 记录一次语义缓存命中（cache=semantic/keyword/prefix）。 */
    public void recordCacheHit(String cache) {
        Counter.builder("a2a_semantic_cache_hits_total")
                .description("语义路由缓存命中总量")
                .tag("cache", sanitize(cache))
                .register(Metrics.globalRegistry)
                .increment();
    }

    /** 记录一次语义缓存未命中。 */
    public void recordCacheMiss(String cache) {
        Counter.builder("a2a_semantic_cache_misses_total")
                .description("语义路由缓存未命中总量")
                .tag("cache", sanitize(cache))
                .register(Metrics.globalRegistry)
                .increment();
    }

    /** ⭐ 记录一次 Graph 执行错误类型（RETRYABLE_FAILED / FATAL_FAILED / NEED_REPLAN）。 */
    public void recordErrorType(String agent, String errorType) {
        Counter.builder("a2a_graph_error_total")
                .description("Graph 节点执行错误总量（按类型分）")
                .tag("agent", sanitize(agent))
                .tag("error_type", sanitize(errorType))
                .register(Metrics.globalRegistry)
                .increment();
    }

    /** Prometheus tag 不允许为空或包含非法字符，统一兜底。 */
    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return AGENT_UNKNOWN;
        return value;
    }
}
