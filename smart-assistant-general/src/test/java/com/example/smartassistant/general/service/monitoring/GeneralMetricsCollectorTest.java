/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.service.monitoring;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GeneralMetricsCollector} 单元测试（使用 {@link SimpleMeterRegistry}）。
 * <p>验证各指标在调用后正确累加，且以 {@code service=general-service} 标签注册。</p>
 */
@DisplayName("[general] GeneralMetricsCollector 指标累加测试")
class GeneralMetricsCollectorTest {

    private SimpleMeterRegistry registry;
    private GeneralMetricsCollector collector;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        collector = new GeneralMetricsCollector(registry);
    }

    private double counter(String name) {
        var c = registry.find(name).tag("service", "general-service").counter();
        assertNotNull(c, "计数器应已注册: " + name);
        return c.count();
    }

    @Test
    @DisplayName("recordTokenUsage：输入/输出 token 计数器累加")
    void recordTokenUsage_incrementsCounters() {
        collector.recordTokenUsage(10, 5);
        collector.recordTokenUsage(2, 3);

        assertEquals(12.0, counter("a2a_llm_token_input_total"));
        assertEquals(8.0, counter("a2a_llm_token_output_total"));
    }

    @Test
    @DisplayName("recordTimeout / recordMaxIterationHit：计数器累加")
    void timeoutAndMaxIteration_increment() {
        collector.recordTimeout();
        collector.recordTimeout();
        collector.recordMaxIterationHit();

        assertEquals(2.0, counter("a2a_agent_timeout_total"));
        assertEquals(1.0, counter("a2a_agent_max_iteration_hit_total"));
    }

    @Test
    @DisplayName("recordContextCompression / recordToolHallucination：计数器累加")
    void contextAndHallucination_increment() {
        collector.recordContextCompression();
        collector.recordToolHallucination();

        assertEquals(1.0, counter("a2a_agent_context_compress_total"));
        assertEquals(1.0, counter("a2a_agent_tool_hallucination_total"));
    }

    @Test
    @DisplayName("recordIteration：迭代计数器累加")
    void recordIteration_increments() {
        collector.recordIteration(1);
        collector.recordIteration(1);
        collector.recordIteration(1);

        assertEquals(3.0, counter("a2a_agent_iteration_total"));
    }
}
