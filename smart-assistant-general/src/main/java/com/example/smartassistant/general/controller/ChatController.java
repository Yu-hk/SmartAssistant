/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.controller;

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
 * 通用对话 Agent REST 端点。
 * <p>
 * 使用统一响应格式 {@link AgentApiResponse} 包装响应。
 * </p>
 */
@RestController
@RequestMapping("/api/agent")
@Slf4j
public class ChatController {

    private final SmartReActAgent generalChatAgent;
    private final AgentErrorMetricsCollector metricsCollector;

    public ChatController(@Qualifier("generalChatAgent") SmartReActAgent generalChatAgent,
                          AgentErrorMetricsCollector metricsCollector) {
        this.generalChatAgent = generalChatAgent;
        this.metricsCollector = metricsCollector;
    }

    @PostMapping("/chat")
    public ResponseEntity<AgentApiResponse<AgentChatResponse>> chat(@RequestBody AgentChatRequest request) {
        log.info("[ChatController] 收到 Router 请求: userId={}, requestId={}, messageLength={}",
                request.userId(), request.requestId(),
                request.message() != null ? request.message().length() : 0);

        if (request.requestId() != null && !request.requestId().isBlank()) {
            ToolLogContext.setRequestId(request.requestId());
        }

        long start = System.currentTimeMillis();
        try {
            String responseText = generalChatAgent.execute(request.message());
            long elapsed = System.currentTimeMillis() - start;

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
                    data, "general_agent", request.requestId(), elapsed));

        } catch (Exception e) {
            log.error("[ChatController] Agent 执行失败: {}", e.getMessage(), e);
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
