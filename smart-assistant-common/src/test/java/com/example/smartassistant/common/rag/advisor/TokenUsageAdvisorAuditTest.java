/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.advisor;

import com.example.smartassistant.common.audit.AiAuditEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TokenUsageAdvisor 审计闭环测试 — 验证“名实不符”已修复：
 * 真实从 ChatResponse 抽取 token 用量 / 模型，并发布结构化 {@link AiAuditEvent}。
 */
@ExtendWith(MockitoExtension.class)
class TokenUsageAdvisorAuditTest {

    @Mock private ChatClientRequest request;
    @Mock private ChatClientResponse response;
    @Mock private ChatResponse chatResponse;
    @Mock private ChatResponseMetadata meta;
    @Mock private Usage usage;
    @Mock private Prompt prompt;
    @Mock private CallAdvisorChain chain;

    @Test
    @DisplayName("TokenUsageAdvisor 真实提取 token 用量并发布 AiAuditEvent（修复名实不符）")
    void publishesAuditEventWithTokens() {
        when(response.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getMetadata()).thenReturn(meta);
        when(meta.getUsage()).thenReturn(usage);
        when(meta.getModel()).thenReturn("deepseek-chat");
        when(usage.getPromptTokens()).thenReturn(12);
        when(usage.getCompletionTokens()).thenReturn(34);
        when(usage.getTotalTokens()).thenReturn(46);
        when(request.prompt()).thenReturn(prompt);
        when(prompt.getContents()).thenReturn("规划一次北京到东京的旅行");
        when(chain.nextCall(any())).thenReturn(response);

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TokenUsageAdvisor advisor = new TokenUsageAdvisor(publisher);

        advisor.adviseCall(request, chain);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        AiAuditEvent event = (AiAuditEvent) captor.getValue();
        assertEquals("deepseek-chat", event.model());
        assertEquals("deepseek", event.provider());
        assertEquals(12, event.promptTokens());
        assertEquals(34, event.completionTokens());
        assertEquals(46, event.totalTokens());
        assertEquals("SUCCESS", event.resultType());
        assertTrue(event.latencyMs() >= 0);
    }

    @Test
    @DisplayName("无 ApplicationEventPublisher 时不发布事件（保持向后兼容）")
    void noPublisherNoEvent(@Mock ChatClientResponse fallback) {
        when(chain.nextCall(any())).thenReturn(fallback);
        TokenUsageAdvisor advisor = new TokenUsageAdvisor(); // 无参构造
        advisor.adviseCall(request, chain);
        // 不抛异常即通过：无 publisher 时跳过发布
    }
}
