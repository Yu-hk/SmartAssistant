/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ChatConsumerService 单元测试
 * 验证核心逻辑的正确性
 */
@ExtendWith(MockitoExtension.class)
class ChatConsumerServiceTest {

    @Mock
    private SessionManagementService sessionManagementService;
    @Mock
    private UserProfileService userProfileService;
    @Mock
    private RouterClient routerClient;
    @Mock
    private RoutingCallLogService routingCallLogService;
    @Mock
    private DistributedTracingService tracingService;
    @Mock
    private DataMaskingService maskingService;
    @Mock
    private EntityProfileService entityProfileService;
    @Mock
    private SentimentAnalysisService sentimentAnalysisService;

    @InjectMocks
    private ChatConsumerService chatConsumerService;

    @Test
    @DisplayName("偏好提取判断：天气查询应被排除")
    void testIsPreferenceWorthyRequest_Weather() throws Exception {
        // 使用反射测试私有方法
        java.lang.reflect.Method method = ChatConsumerService.class
                .getDeclaredMethod("isPreferenceWorthyRequest", String.class);
        method.setAccessible(true);

        // 天气类查询 → 不应提取偏好
        assertFalse((Boolean) method.invoke(chatConsumerService, "北京今天天气怎么样"));
        assertFalse((Boolean) method.invoke(chatConsumerService, "明天几度"));
        assertFalse((Boolean) method.invoke(chatConsumerService, "PM2.5是多少"));
    }

    @Test
    @DisplayName("偏好提取判断：问候语应被排除")
    void testIsPreferenceWorthyRequest_Greeting() throws Exception {
        java.lang.reflect.Method method = ChatConsumerService.class
                .getDeclaredMethod("isPreferenceWorthyRequest", String.class);
        method.setAccessible(true);

        // 问候语 → 不应提取偏好
        assertFalse((Boolean) method.invoke(chatConsumerService, "你好"));
        assertFalse((Boolean) method.invoke(chatConsumerService, "谢谢"));
    }

    @Test
    @DisplayName("偏好提取判断：知识问答应被排除")
    void testIsPreferenceWorthyRequest_Knowledge() throws Exception {
        java.lang.reflect.Method method = ChatConsumerService.class
                .getDeclaredMethod("isPreferenceWorthyRequest", String.class);
        method.setAccessible(true);

        // 知识问答 → 不应提取偏好
        assertFalse((Boolean) method.invoke(chatConsumerService, "什么是递归算法"));
        assertFalse((Boolean) method.invoke(chatConsumerService, "怎么学好Java"));
    }

    @Test
    @DisplayName("偏好提取判断：偏好类查询应被保留")
    void testIsPreferenceWorthyRequest_Preference() throws Exception {
        java.lang.reflect.Method method = ChatConsumerService.class
                .getDeclaredMethod("isPreferenceWorthyRequest", String.class);
        method.setAccessible(true);

        // 偏好类查询 → 应提取偏好
        assertTrue((Boolean) method.invoke(chatConsumerService, "推荐几家成都好吃的火锅店"));
        assertTrue((Boolean) method.invoke(chatConsumerService, "我想去杭州旅游"));
    }

    @Test
    @DisplayName("偏好提取判断：空值返回 false")
    void testIsPreferenceWorthyRequest_Null() throws Exception {
        java.lang.reflect.Method method = ChatConsumerService.class
                .getDeclaredMethod("isPreferenceWorthyRequest", String.class);
        method.setAccessible(true);

        assertFalse((Boolean) method.invoke(chatConsumerService, new Object[]{null}));
        assertFalse((Boolean) method.invoke(chatConsumerService, ""));
    }

    @Test
    void calculateShouldPersistAuthenticatedUserAndManagedThreadSeparately() {
        when(sessionManagementService.getOrCreateThreadId("42")).thenReturn("thread-42");
        when(routerClient.callRouterRaw(
                "北京天气", "42", null, "request-1", null, null))
                .thenReturn(Map.of("result", "晴", "agentName", "none"));

        assertEquals("晴", chatConsumerService.calculate("42", "北京天气", "request-1"));

        verify(routingCallLogService).saveLog(
                eq(42L), eq("thread-42"), eq("北京天气"), eq("router_service"),
                eq("ROUTER_SERVICE"), anyLong(), eq("SUCCESS"));
    }

    @Test
    void calculateWithSessionShouldPersistAuthenticatedUserAndExplicitSessionSeparately() {
        when(sessionManagementService.getOrCreateThreadId("42")).thenReturn("thread-42");
        when(sentimentAnalysisService.analyze("北京天气", "session-a"))
                .thenReturn(new SentimentAnalysisService.SentimentResult(
                        2, "中性", "正常回复", false, false, 100));
        when(routerClient.callRouterRaw(
                "北京天气", "42", "session-a", "request-2", null, null))
                .thenReturn(Map.of("result", "晴", "agentName", "none"));

        assertEquals("晴", chatConsumerService
                .calculateWithSession("42", "北京天气", "session-a", "request-2")
                .get("result"));

        verify(routingCallLogService).saveLog(
                eq(42L), eq("session-a"), eq("北京天气"), eq("router_service"),
                eq("ROUTER_SERVICE"), anyLong(), eq("SUCCESS"));
    }
}
