/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.advisor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.slf4j.MDC;
import java.util.List;
import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PromptAuditAdvisor} 单元测试。
 *
 * @author Yu-hk
 * @since 2026-07-07
 */
class PromptAuditAdvisorTest {

    private final PromptAuditAdvisor advisor = new PromptAuditAdvisor();

    @Test
    @DisplayName("name 返回 'PromptAuditAdvisor'")
    void testName() {
        assertEquals("PromptAuditAdvisor", advisor.getName());
    }

    @Test
    @DisplayName("Order 为 100")
    void testOrder() {
        assertEquals(100, advisor.getOrder());
    }

    @Test
    @DisplayName("审计 Advisor 复用 Spring AI 原生 SimpleLoggerAdvisor")
    void delegatesToSpringAiLoggerMiddleware() {
        assertInstanceOf(SimpleLoggerAdvisor.class, advisor);
    }

    @Test void neverCopiesProfileOrResponseTextIntoDiagnosticLog() {
        var request = mock(ChatClientRequest.class);
        when(request.prompt()).thenReturn(new Prompt("画像：偏好安静环境，预算较低；private-canary"));
        var response = new ChatResponse(List.of(new Generation(new AssistantMessage("按历史偏好推荐 private-canary"))));
        assertEquals("[PromptAudit][requestId=-] request messages=1", PromptAuditAdvisor.requestToString(request));
        assertEquals("[PromptAudit][requestId=-] response generations=1", PromptAuditAdvisor.responseToString(response));
    }

    @Test void rejectsUnboundedOrMultilineTraceAndHandlesMissingBodies() {
        MDC.put("requestId", "private-canary\nforged-log");
        try {
            assertEquals("[PromptAudit][requestId=invalid] request messages=0", PromptAuditAdvisor.requestToString(null));
            assertEquals("[PromptAudit][requestId=invalid] response generations=0", PromptAuditAdvisor.responseToString(null));
        } finally { MDC.remove("requestId"); }
    }
}
