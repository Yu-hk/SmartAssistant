/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 */

package com.example.smartassistant.consumer.service.infrastructure;

import com.example.smartassistant.common.audit.ToolUsageCache;
import com.example.smartassistant.common.audit.ToolUsageHeaders;
import com.example.smartassistant.consumer.entity.RoutingCallLog;
import com.example.smartassistant.consumer.mapper.RoutingCallLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RoutingCallLogServiceTest {

    @Mock
    private RoutingCallLogMapper mapper;

    @Test
    void persistsActualAgentAndBoundedResponseSummary() {
        RoutingCallLogService service = new RoutingCallLogService(mapper);
        String response = "x".repeat(700);

        service.saveLog(
                7L, "session-a", "北京天气", "weather_service",
                "ROUTER_SERVICE", 123L, "SUCCESS", response,
                120L, 30L, 150L);

        ArgumentCaptor<RoutingCallLog> captor = ArgumentCaptor.forClass(RoutingCallLog.class);
        verify(mapper).insert(captor.capture());
        RoutingCallLog saved = captor.getValue();
        assertEquals(7L, saved.getUserId());
        assertEquals("session-a", saved.getSessionId());
        assertEquals("weather_service", saved.getRoutedAgent());
        assertEquals("SUCCESS", saved.getStatus());
        assertEquals(500, saved.getResponseSummary().length());
        assertEquals(120L, saved.getPromptTokens());
        assertEquals(30L, saved.getCompletionTokens());
        assertEquals(150L, saved.getTotalTokens());
    }

    @Test
    void persistsSanitizedPromptAndArgumentFreeToolTelemetry() {
        RoutingCallLogService service = new RoutingCallLogService(mapper);

        service.saveLog(
                9L, "session-b", "查询天气", "weather_service",
                "ROUTER_SERVICE", 88L, "SUCCESS", "晴",
                10L, 2L, 12L,
                "查询天气 password=hidden",
                new ToolUsageCache.ToolUsage(true, List.of(
                        new ToolUsageCache.ToolCall("queryWeather", "SUCCESS", 20))));

        ArgumentCaptor<RoutingCallLog> captor = ArgumentCaptor.forClass(RoutingCallLog.class);
        verify(mapper).insert(captor.capture());
        RoutingCallLog saved = captor.getValue();
        assertEquals("查询天气 password=[REDACTED]", saved.getLlmReceivedQuestion());
        ToolUsageCache.ToolUsage decoded = ToolUsageHeaders.decode(saved.getToolCalls());
        assertEquals(1, decoded.calls().size());
        assertEquals("queryWeather", decoded.calls().getFirst().name());
    }
}
