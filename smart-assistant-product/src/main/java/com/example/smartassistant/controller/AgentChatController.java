/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.controller;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.agent.dto.AgentChatRequest;
import com.example.smartassistant.common.agent.dto.AgentChatResponse;
import com.example.smartassistant.common.api.AgentApiResponse;
import com.example.smartassistant.common.api.AgentApiResponses;
import com.example.smartassistant.common.api.AgentError;
import com.example.smartassistant.common.monitoring.AgentErrorMetricsCollector;
import com.example.smartassistant.common.tool.ToolLogContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 统一 REST 端点（美食推荐 Agent）。
 * <p>Router 通过 {@code POST /api/agent/chat} 调用此端点。</p>
 * <p>响应格式使用统一的 {@link AgentApiResponse} 包装，兼容新旧调用方。</p>
 */
@RestController
@RequestMapping("/api/agent")
@Slf4j
public class AgentChatController {

    private final SmartReActAgent productAgent;
    private final AgentErrorMetricsCollector metricsCollector;

    public AgentChatController(@Qualifier("productAgent") SmartReActAgent productAgent,
                               AgentErrorMetricsCollector metricsCollector) {
        this.productAgent = productAgent;
        this.metricsCollector = metricsCollector;
    }

    /**
     * 接收 Router 转发来的消息，由 Agent 处理后返回结果。
     */
    @PostMapping("/chat")
    public ResponseEntity<AgentApiResponse<AgentChatResponse>> chat(@RequestBody AgentChatRequest request) {
        log.info("[AgentChat] 收到 Router 请求: userId={}, requestId={}, messageLength={}",
                request.userId(), request.requestId(),
                request.message() != null ? request.message().length() : 0);

        if (request.requestId() != null && !request.requestId().isBlank()) {
            ToolLogContext.setRequestId(request.requestId());
        }

        long start = System.currentTimeMillis();
        try {
            String responseText = productAgent.execute(request.message());
            long elapsed = System.currentTimeMillis() - start;
            log.info("[AgentChat] 处理完成 (耗时={}ms, 响应长度={})", elapsed,
                    responseText != null ? responseText.length() : 0);

            if (responseText == null || responseText.isBlank()) {
                AgentError error = AgentError.builder()
                        .code(AgentApiResponses.ERROR_AGENT_FAILED)
                        .title("Agent 返回为空")
                        .detail("Agent 返回为空")
                        .build();
                metricsCollector.recordError(error);
                return ResponseEntity.ok(AgentApiResponses.error(error, request.requestId(), elapsed));
            }

            AgentChatResponse data = AgentChatResponse.ok(responseText);
            return ResponseEntity.ok(AgentApiResponses.ok(
                    data, "product_agent", request.requestId(), elapsed));

        } catch (Exception e) {
            log.error("[AgentChat] Agent 执行失败: {}", e.getMessage(), e);
            AgentError error = AgentError.builder()
                    .code(AgentApiResponses.ERROR_AGENT_FAILED)
                    .title("Agent 执行失败")
                    .detail("Agent 执行失败: " + e.getMessage())
                    .build();
            metricsCollector.recordError(error);
            return ResponseEntity.internalServerError().body(
                    AgentApiResponses.error(error, request.requestId(), System.currentTimeMillis() - start));
        } finally {
            ToolLogContext.clear();
        }
    }
}
