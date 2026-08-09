/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 */

package com.example.smartassistant.consumer.service.core;

import com.example.smartassistant.common.memory.EntityProfileService;
import com.example.smartassistant.common.sentiment.SentimentAnalysisService;
import com.example.smartassistant.common.tracing.DistributedTracingService;
import com.example.smartassistant.consumer.client.RouterClient;
import com.example.smartassistant.consumer.service.infrastructure.DataMaskingService;
import com.example.smartassistant.consumer.service.infrastructure.RoutingCallLogService;
import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
import com.example.smartassistant.consumer.service.session.SessionManagementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatConsumerServiceTest {

    @Mock private SessionManagementService sessionManagementService;
    @Mock private UserProfileService userProfileService;
    @Mock private RouterClient routerClient;
    @Mock private RoutingCallLogService routingCallLogService;
    @Mock private DistributedTracingService tracingService;
    @Mock private DataMaskingService maskingService;
    @Mock private EntityProfileService entityProfileService;
    @Mock private SentimentAnalysisService sentimentAnalysisService;

    @InjectMocks
    private ChatConsumerService chatConsumerService;

    @Test
    void temporaryOrGenericQuestionsAreNotStoredAsPreferences() throws Exception {
        Method method = ChatConsumerService.class
                .getDeclaredMethod("isPreferenceWorthyRequest", String.class);
        method.setAccessible(true);

        assertFalse((Boolean) method.invoke(chatConsumerService, "北京今天天气怎么样"));
        assertFalse((Boolean) method.invoke(chatConsumerService, "你好"));
        assertFalse((Boolean) method.invoke(chatConsumerService, "什么是递归算法"));
        assertFalse((Boolean) method.invoke(chatConsumerService, new Object[]{null}));
        assertTrue((Boolean) method.invoke(chatConsumerService, "推荐几家成都好吃的火锅店"));
    }

    @Test
    void calculatePersistsActualAgentAndResponseSummary() {
        when(sessionManagementService.getOrCreateThreadId("42")).thenReturn("thread-42");
        when(routerClient.callRouterRaw(
                "北京天气", "42", null, "request-1", null, null))
                .thenReturn(Map.of(
                        "result", "今天晴朗",
                        "agentName", "weather_service",
                        "intentTag", "weather",
                        "promptTokens", 40,
                        "completionTokens", 10,
                        "totalTokens", 50));

        assertEquals("今天晴朗",
                chatConsumerService.calculate("42", "北京天气", "request-1"));

        verify(routingCallLogService).saveLog(
                eq(42L), eq("thread-42"), eq("北京天气"), eq("weather_service"),
                eq("ROUTER_SERVICE"), anyLong(), eq("SUCCESS"), eq("今天晴朗"),
                eq(40L), eq(10L), eq(50L), eq("北京天气"), isNull());
    }

    @Test
    void calculateWithSessionPersistsExplicitSessionAndActualAgent() {
        when(sessionManagementService.getOrCreateThreadId("42")).thenReturn("thread-42");
        when(sentimentAnalysisService.analyze("北京天气", "session-a"))
                .thenReturn(new SentimentAnalysisService.SentimentResult(
                        2, "中性", "正常回复", false, false, 100));
        when(routerClient.callRouterRaw(
                "北京天气", "42", "session-a", "request-2", null, null))
                .thenReturn(Map.of(
                        "result", "今天晴朗",
                        "agentName", "weather_service",
                        "intentTag", "weather",
                        "prompt_tokens", 60,
                        "completion_tokens", 15,
                        "total_tokens", 75));

        assertEquals("今天晴朗", chatConsumerService
                .calculateWithSession("42", "北京天气", "session-a", "request-2")
                .get("result"));

        verify(routingCallLogService).saveLog(
                eq(42L), eq("session-a"), eq("北京天气"), eq("weather_service"),
                eq("ROUTER_SERVICE"), anyLong(), eq("SUCCESS"), eq("今天晴朗"),
                eq(60L), eq(15L), eq(75L), eq("北京天气"), isNull());
    }

    @Test
    void nullErrorFieldDoesNotMarkSuccessfulRouterResponseAsFailed() {
        when(sessionManagementService.getOrCreateThreadId("42")).thenReturn("thread-42");
        when(sentimentAnalysisService.analyze("hello", "session-null-error"))
                .thenReturn(new SentimentAnalysisService.SentimentResult(
                        2, "neutral", "normal", false, false, 100));
        Map<String, Object> routerResponse = new HashMap<>();
        routerResponse.put("result", "hi");
        routerResponse.put("agentName", "general_service");
        routerResponse.put("error", null);
        routerResponse.put("totalTokens", 9L);
        when(routerClient.callRouterRaw(
                "hello", "42", "session-null-error", "request-null-error", null, null))
                .thenReturn(routerResponse);

        chatConsumerService.calculateWithSession(
                "42", "hello", "session-null-error", "request-null-error");

        verify(routingCallLogService).saveLog(
                eq(42L), eq("session-null-error"), eq("hello"), eq("general_service"),
                eq("ROUTER_SERVICE"), anyLong(), eq("SUCCESS"), eq("hi"),
                eq((Long) null), eq((Long) null), eq(9L), eq("hello"), isNull());
    }

    @Test
    void requiredParameterClarificationIsPersistedAsPartialSuccess() {
        when(sessionManagementService.getOrCreateThreadId("42")).thenReturn("thread-42");
        when(sentimentAnalysisService.analyze("查询退款进度", "session-refund"))
                .thenReturn(new SentimentAnalysisService.SentimentResult(
                        2, "中性", "正常回复", false, false, 100));
        when(routerClient.callRouterRaw(
                "查询退款进度", "42", "session-refund", "request-refund", null, null))
                .thenReturn(Map.of(
                        "result", "请提供订单号（格式：ORD-xxx）以便查询退款信息。",
                        "agentName", "order",
                        "intentTag", "退款申请",
                        "clarification", true));

        chatConsumerService.calculateWithSession(
                "42", "查询退款进度", "session-refund", "request-refund");

        verify(routingCallLogService).saveLog(
                eq(42L), eq("session-refund"), eq("查询退款进度"), eq("order"),
                eq("ROUTER_SERVICE"), anyLong(), eq("PARTIAL_SUCCESS"),
                eq("请提供订单号（格式：ORD-xxx）以便查询退款信息。"),
                eq((Long) null), eq((Long) null), eq((Long) null),
                eq("查询退款进度"), isNull());
    }

    @Test
    void missingSessionUsesRequestIdAsStableConversationKey() {
        when(sessionManagementService.getOrCreateThreadId("42")).thenReturn("thread-42");
        when(sentimentAnalysisService.analyze("hello", "request-without-session"))
                .thenReturn(new SentimentAnalysisService.SentimentResult(
                        2, "中性", "正常回复", false, false, 100));
        when(routerClient.callRouterRaw(
                "hello", "42", "request-without-session", "request-without-session", null, null))
                .thenReturn(Map.of(
                        "result", "hi",
                        "agentName", "general_service",
                        "totalTokens", 9));

        Map<String, Object> response = chatConsumerService.calculateWithSession(
                "42", "hello", null, "request-without-session");

        assertEquals("request-without-session", response.get("sessionId"));
        verify(routerClient).callRouterRaw(
                "hello", "42", "request-without-session", "request-without-session", null, null);
        verify(routingCallLogService).saveLog(
                eq(42L), eq("request-without-session"), eq("hello"), eq("general_service"),
                eq("ROUTER_SERVICE"), anyLong(), eq("SUCCESS"), eq("hi"),
                eq((Long) null), eq((Long) null), eq(9L), eq("hello"), isNull());
    }

    @Test
    void sentimentHandoffIsAlsoPersistedAsACompletedTurn() {
        when(sessionManagementService.getOrCreateThreadId("42")).thenReturn("thread-42");
        when(sentimentAnalysisService.analyze("我要投诉", "session-handoff"))
                .thenReturn(new SentimentAnalysisService.SentimentResult(
                        5, "愤怒", "转人工", true, true, 100));
        when(sentimentAnalysisService.getHandoffResponse(5)).thenReturn("正在为您转接人工客服");

        Map<String, Object> response = chatConsumerService.calculateWithSession(
                "42", "我要投诉", "session-handoff", "request-3");

        assertEquals("human_service", response.get("agentName"));
        verify(routingCallLogService).saveLog(
                eq(42L), eq("session-handoff"), eq("我要投诉"), eq("human_service"),
                eq("SENTIMENT_HANDOFF"), anyLong(), eq("SUCCESS"),
                eq("正在为您转接人工客服"), eq(0L), eq(0L), eq(0L), eq("我要投诉"),
                argThat(usage -> usage.complete() && usage.calls().isEmpty()));
    }
}
