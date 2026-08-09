/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.advisor;

import com.example.smartassistant.common.audit.AiAuditEvent;
import com.example.smartassistant.common.audit.TokenUsageCache;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TokenUsageAdvisorAuditTest {

    @Test
    void publishesAuditEventWithTokens() {
        ChatClientRequest request = mock(ChatClientRequest.class);
        ChatClientResponse response = responseWithUsage(12, 34, 46);
        ChatResponse chatResponse = response.chatResponse();
        when(chatResponse.getMetadata().getModel()).thenReturn("deepseek-chat");
        Prompt prompt = mock(Prompt.class);
        when(request.prompt()).thenReturn(prompt);
        when(prompt.getContents()).thenReturn("plan a trip");
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(response);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        new TokenUsageAdvisor(publisher).adviseCall(request, chain);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        AiAuditEvent event = (AiAuditEvent) captor.getValue();
        assertEquals("deepseek-chat", event.model());
        assertEquals("deepseek", event.provider());
        assertEquals(12, event.promptTokens());
        assertEquals(34, event.completionTokens());
        assertEquals(46, event.totalTokens());
        assertEquals("SUCCESS", event.resultType());
    }

    @Test
    void noPublisherDoesNotFail() {
        ChatClientRequest request = mock(ChatClientRequest.class);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientResponse response = mock(ChatClientResponse.class);
        when(chain.nextCall(any())).thenReturn(response);
        assertDoesNotThrow(() -> new TokenUsageAdvisor().adviseCall(request, chain));
    }

    @Test
    void unmeasurablePartialUsageRemainsUnknown() {
        ChatClientRequest request = mock(ChatClientRequest.class);
        ChatClientResponse response = responseWithUsage(12, null, null);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(response);
        MDC.put("requestId", "partial-usage-request");
        try {
            new TokenUsageAdvisor().adviseCall(request, chain);
            assertNull(TokenUsageCache.consume("partial-usage-request"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void nullableProviderUsageDoesNotBreakSuccessfulModelResponse() {
        ChatClientRequest request = mock(ChatClientRequest.class);
        ChatClientResponse response = responseWithUsage(new Usage() {
            @Override
            public Integer getPromptTokens() {
                return null;
            }

            @Override
            public Integer getCompletionTokens() {
                return 17;
            }

            @Override
            public Integer getTotalTokens() {
                throw new NullPointerException("provider attempted to unbox a missing token component");
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }
        });
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(response);
        MDC.put("requestId", "nullable-provider-usage");
        try {
            assertSame(response, new TokenUsageAdvisor().adviseCall(request, chain));
            assertNull(TokenUsageCache.consume("nullable-provider-usage"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void totalOnlyUsageIsPreservedWithoutInventingComponents() {
        ChatClientRequest request = mock(ChatClientRequest.class);
        ChatClientResponse response = responseWithUsage(null, null, 21);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(response);
        MDC.put("requestId", "total-only-request");
        try {
            new TokenUsageAdvisor().adviseCall(request, chain);
            var captured = TokenUsageCache.consume("total-only-request");
            assertNotNull(captured);
            assertNull(captured.promptTokens());
            assertNull(captured.completionTokens());
            assertEquals(21, captured.totalTokens());
        } finally {
            MDC.clear();
        }
    }

    @Test
    void streamKeepsEarlierMeasuredUsageWhenLastChunkHasNoUsage() {
        ChatClientRequest request = mock(ChatClientRequest.class);
        ChatClientResponse measured = responseWithUsage(14, 5, 19);
        ChatClientResponse trailing = responseWithUsage(null, null, null);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(measured, trailing));
        MDC.put("requestId", "stream-usage-request");
        try {
            new TokenUsageAdvisor().adviseStream(request, chain).collectList().block();
            var captured = TokenUsageCache.consume("stream-usage-request");
            assertNotNull(captured);
            assertEquals(14, captured.promptTokens());
            assertEquals(5, captured.completionTokens());
            assertEquals(19, captured.totalTokens());
        } finally {
            MDC.clear();
        }
    }

    private static ChatClientResponse responseWithUsage(Integer prompt, Integer completion, Integer total) {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(prompt);
        when(usage.getCompletionTokens()).thenReturn(completion);
        when(usage.getTotalTokens()).thenReturn(total);
        return responseWithUsage(usage);
    }

    private static ChatClientResponse responseWithUsage(Usage usage) {
        ChatClientResponse response = mock(ChatClientResponse.class);
        ChatResponse chatResponse = mock(ChatResponse.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(response.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getMetadata()).thenReturn(metadata);
        when(metadata.getUsage()).thenReturn(usage);
        return response;
    }
}
