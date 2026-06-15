/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.monitoring;

import com.example.smartassistant.common.api.AgentError;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 统一的 AgentError 指标收集器。
 * <p>用于监控各模块返回的错误码分布，生成 {@code agent_error_total{error_code="..."}} Prometheus Counter。</p>
 *
 * <p>由各模块的 {@code GlobalExceptionHandler} 在构造错误响应时调用，
 * 确保每次结构化错误返回都被计数，便于监控告警。</p>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * AgentError error = AgentError.builder()
 *         .code("GENERAL_001")
 *         .title("请求参数校验失败")
 *         .build();
 * metricsCollector.recordError(error);
 * return ResponseEntity.badRequest().body(AgentApiResponses.error(error, null, 0));
 * }</pre>
 *
 * @see AgentError
 * @see com.example.smartassistant.common.api.AgentApiResponses
 */
@Component
public class AgentErrorMetricsCollector {

    private final MeterRegistry meterRegistry;

    public AgentErrorMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 记录一次 AgentError 返回事件。
     * <p>递增 Prometheus Counter {@code agent_error_total}，标签 {@code error_code} 为错误码值。</p>
     *
     * @param error 错误对象；为空或缺少 code 时静默忽略
     */
    public void recordError(AgentError error) {
        if (error == null || error.getCode() == null || error.getCode().isBlank()) {
            return;
        }
        meterRegistry.counter("agent_error_total",
                        "error_code", error.getCode())
                .increment();
    }
}
