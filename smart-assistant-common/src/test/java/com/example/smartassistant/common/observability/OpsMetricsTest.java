/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.observability;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OpsMetrics 指标累加行为验证：确认 6 类运营指标正确写入 Micrometer 全局注册表。
 */
class OpsMetricsTest {

    private SimpleMeterRegistry registry;
    private OpsMetrics opsMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        opsMetrics = new OpsMetrics();
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(registry);
    }

    @Test
    @DisplayName("工具调用：成功/失败分别累加 outcome 标签")
    void recordToolCallSplitsOutcome() {
        opsMetrics.recordToolCall("order", true);
        opsMetrics.recordToolCall("order", true);
        opsMetrics.recordToolCall("order", false);

        double success = registry.get("a2a_tool_calls_total").tag("outcome", "success").counter().count();
        double failure = registry.get("a2a_tool_calls_total").tag("outcome", "failure").counter().count();

        assertEquals(2.0, success);
        assertEquals(1.0, failure);
    }

    @Test
    @DisplayName("路由延迟：写入 Timer 且计数+1")
    void recordRouteLatency() {
        opsMetrics.recordRouteLatency("router", "weather", 123L);

        double count = registry.get("a2a_route_latency_seconds").timer().count();
        assertEquals(1.0, count);
    }

    @Test
    @DisplayName("单轮 Token：仅正值计入 DistributionSummary")
    void recordTurnTokensIgnoresNonPositive() {
        opsMetrics.recordTurnTokens("product", 512);
        opsMetrics.recordTurnTokens("product", 0);
        opsMetrics.recordTurnTokens("product", -10);

        double count = registry.get("a2a_turn_tokens").summary().count();
        assertEquals(1.0, count);
        assertEquals(512.0, registry.get("a2a_turn_tokens").summary().totalAmount());
    }

    @Test
    @DisplayName("应答/无证据拒答：分别累加")
    void recordAnswersAndNoEvidence() {
        opsMetrics.recordAnswer("order", "refund");
        opsMetrics.recordNoEvidenceAnswer("order", "refund");

        assertEquals(1.0, registry.get("a2a_answers_total").counter().count());
        assertEquals(1.0, registry.get("a2a_answers_no_evidence_total").counter().count());
    }

    @Test
    @DisplayName("人工接管：按 reason 累加")
    void recordHandoff() {
        opsMetrics.recordHandoff("emotion_heavy", "general_agent");
        assertEquals(1.0, registry.get("a2a_handoffs_total").tag("reason", "emotion_heavy").counter().count());
    }

    @Test
    @DisplayName("缓存命中/未命中：分别累加")
    void recordCacheHitAndMiss() {
        opsMetrics.recordCacheHit("semantic");
        opsMetrics.recordCacheMiss("semantic");

        assertEquals(1.0, registry.get("a2a_semantic_cache_hits_total").counter().count());
        assertEquals(1.0, registry.get("a2a_semantic_cache_misses_total").counter().count());
    }

    @Test
    @DisplayName("空 tag 兜底为 unknown，不抛异常")
    void sanitizeNullTag() {
        opsMetrics.recordAnswer(null, "  ");
        assertEquals(1.0, registry.get("a2a_answers_total").tag("agent", "unknown").counter().count());
    }
}
