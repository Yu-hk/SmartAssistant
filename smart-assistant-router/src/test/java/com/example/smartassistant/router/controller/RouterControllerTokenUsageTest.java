/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.controller;

import com.example.smartassistant.common.audit.TokenUsageCache;
import com.example.smartassistant.common.audit.ToolUsageCache;
import com.example.smartassistant.common.tracing.DistributedTracingService;
import com.example.smartassistant.router.model.RouteRequest;
import com.example.smartassistant.router.model.RoutingResult;
import com.example.smartassistant.router.service.core.RouterService;
import com.example.smartassistant.router.service.tool.RoutingToolChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RouterControllerTokenUsageTest {

    @Test
    void returnsAndConsumesCompleteRequestUsage() {
        RouterService routerService = mock(RouterService.class);
        RouteRequest request = RouteRequest.builder()
                .requestId("router-request-usage")
                .userId(1L)
                .question("hello")
                .build();
        when(routerService.route(request)).thenAnswer(ignored -> {
            TokenUsageCache.record("router-request-usage", "router-call", 8, 2, 10);
            TokenUsageCache.record("router-request-usage", "agent-call", 20, 7, 27);
            ToolUsageCache.record("router-request-usage", "webSearch", true, 35);
            return RoutingResult.builder()
                    .result("hi")
                    .agentName("general_agent")
                    .confidence(0.9)
                    .clarification(true)
                    .build();
        });
        RouterController controller = new RouterController(
                routerService,
                mock(DistributedTracingService.class),
                mock(RoutingToolChecker.class),
                null);

        var response = controller.route(request).getData();

        assertEquals(28, response.getPromptTokens());
        assertEquals(9, response.getCompletionTokens());
        assertEquals(37, response.getTotalTokens());
        assertEquals(true, response.getToolUsageComplete());
        assertEquals("webSearch", response.getToolCalls().getFirst().name());
        assertEquals(true, response.getClarification());
        assertNull(TokenUsageCache.consume("router-request-usage"));
        assertNull(ToolUsageCache.consume("router-request-usage"));
    }
}
