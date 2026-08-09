/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.agent;

import com.example.smartassistant.common.audit.TokenUsageCache;
import com.example.smartassistant.common.audit.TokenUsageHeaders;
import com.example.smartassistant.common.audit.ToolUsageCache;
import com.example.smartassistant.common.audit.ToolUsageHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentCallerTokenUsageTest {

    @Test
    void aggregatesMeasuredUsageFromMultipleAgentResponses() {
        HttpHeaders first = headers(10, 4, 14);
        HttpHeaders second = headers(20, 6, 26);

        AgentCallerService.recordDownstreamTokenUsage("route-token-1", first);
        AgentCallerService.recordDownstreamTokenUsage("route-token-1", second);

        var usage = TokenUsageCache.consume("route-token-1");
        assertEquals(30, usage.promptTokens());
        assertEquals(10, usage.completionTokens());
        assertEquals(40, usage.totalTokens());
    }

    @Test
    void absentHeadersRemainUnknown() {
        TokenUsageCache.record("route-token-2", "router-local", 6, 2, 8);
        AgentCallerService.recordDownstreamTokenUsage("route-token-2", new HttpHeaders());
        assertNull(TokenUsageCache.consume("route-token-2"));
    }

    @Test
    void totalOnlyIsPreservedWhileUnmeasurablePartialHeadersRemainUnknown() {
        HttpHeaders partial = new HttpHeaders();
        partial.set(TokenUsageHeaders.TOTAL_TOKENS, "42");
        AgentCallerService.recordDownstreamTokenUsage("route-token-partial", partial);

        HttpHeaders malformed = headers(10, 3, 13);
        malformed.set(TokenUsageHeaders.COMPLETION_TOKENS, "not-a-number");
        malformed.remove(TokenUsageHeaders.TOTAL_TOKENS);
        AgentCallerService.recordDownstreamTokenUsage("route-token-malformed", malformed);

        var totalOnly = TokenUsageCache.consume("route-token-partial");
        assertNull(totalOnly.promptTokens());
        assertNull(totalOnly.completionTokens());
        assertEquals(42, totalOnly.totalTokens());
        assertNull(TokenUsageCache.consume("route-token-malformed"));
    }

    @Test
    void derivesTotalWhenBothComponentsArePresent() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TokenUsageHeaders.PROMPT_TOKENS, "9");
        headers.set(TokenUsageHeaders.COMPLETION_TOKENS, "4");
        AgentCallerService.recordDownstreamTokenUsage("route-token-derived", headers);
        assertEquals(13, TokenUsageCache.consume("route-token-derived").totalTokens());
    }

    @Test
    void aggregatesArgumentFreeToolTelemetryFromAgentHeader() {
        ToolUsageCache.start("route-tools");
        HttpHeaders headers = new HttpHeaders();
        headers.set(ToolUsageHeaders.TOOL_USAGE, ToolUsageHeaders.encode(
                new ToolUsageCache.ToolUsage(true, List.of(
                        new ToolUsageCache.ToolCall("queryOrder", "SUCCESS", 17)))));

        AgentCallerService.recordDownstreamToolUsage("route-tools", headers);

        ToolUsageCache.ToolUsage usage = ToolUsageCache.consume("route-tools");
        assertEquals(1, usage.calls().size());
        assertEquals("queryOrder", usage.calls().getFirst().name());
        assertEquals(17, usage.calls().getFirst().durationMs());
    }

    @Test
    void missingToolHeaderMarksRequestIncompleteInsteadOfNoTools() {
        ToolUsageCache.start("route-tools-missing");

        AgentCallerService.recordDownstreamToolUsage("route-tools-missing", new HttpHeaders());

        ToolUsageCache.ToolUsage usage = ToolUsageCache.consume("route-tools-missing");
        assertEquals(false, usage.complete());
        assertEquals(List.of(), usage.calls());
    }

    private static HttpHeaders headers(long prompt, long completion, long total) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TokenUsageHeaders.PROMPT_TOKENS, String.valueOf(prompt));
        headers.set(TokenUsageHeaders.COMPLETION_TOKENS, String.valueOf(completion));
        headers.set(TokenUsageHeaders.TOTAL_TOKENS, String.valueOf(total));
        return headers;
    }
}
