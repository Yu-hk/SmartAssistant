/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.client.AgentStreamClient;
import com.example.smartassistant.consumer.client.RouterClient;
import com.example.smartassistant.consumer.service.core.RequestQueueService;
import com.example.smartassistant.consumer.service.infrastructure.RoutingCallLogService;
import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
import com.example.smartassistant.consumer.service.session.ConversationGateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.test.util.ReflectionTestUtils;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamChatControllerPersistenceTest {

    @Mock private RouterClient routerClient;
    @Mock private AgentStreamClient agentStreamClient;
    @Mock private RequestQueueService requestQueueService;
    @Mock private RoutingCallLogService routingCallLogService;
    @Mock private UserProfileService userProfileService;
    @Mock private ConversationGateService conversationGateService;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void emitsMeasuredToolSnapshotAndTokensBeforeDoneWithoutToolArguments() throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("X-User-Id", "42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
        when(routerClient.waitForDecisionFromRedis(eq("telemetry"), eq(60_000L), any(Runnable.class)))
                .thenReturn(Map.of("agentName", "product", "result", "查询完成",
                        "totalTokens", 100, "promptTokens", 80, "completionTokens", 20,
                        "toolUsageComplete", true, "toolCalls", List.of(Map.of(
                                "name", "queryProductInfo", "status", "SUCCESS", "durationMs", 36,
                                "arguments", "private-order-data", "result", "private-tool-result"))));
        StreamChatController controller = new StreamChatController(
                routerClient, agentStreamClient, requestQueueService, routingCallLogService, null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.streamChatPost(Map.of("message", "查询商品", "requestId", "telemetry", "sessionId", "owned"), response);
        String events = response.getContentAsString();
        assertTrue(events.contains("event: tool_usage"));
        assertTrue(events.contains("\"toolUsageComplete\":true"));
        assertTrue(events.contains("\"durationMs\":36"));
        assertTrue(events.contains("\"totalTokens\":100"));
        assertTrue(events.indexOf("event: tool_usage") < events.indexOf("event: done"));
        assertTrue(events.indexOf("event: token_usage") < events.indexOf("event: done"));
        assertFalse(events.contains("private-order-data"));
        assertFalse(events.contains("private-tool-result"));
    }

    @Test
    void completedSseTurnPersistsOwnerSessionAgentAndResponse() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("X-User-Id", "42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));

        when(routerClient.waitForDecisionFromRedis(eq("request-1"), eq(60_000L), any(Runnable.class)))
                .thenReturn(Map.of(
                        "agentName", "product_service",
                        "confidence", 0.98,
                        "intentTag", "product",
                        "result", "当前有 3 个热门商品",
                        "promptTokens", 24,
                        "completionTokens", 6,
                        "totalTokens", 30));
        StreamChatController controller = new StreamChatController(
                routerClient, agentStreamClient, requestQueueService,
                routingCallLogService, null);
        ReflectionTestUtils.setField(controller, "userProfileService", userProfileService);

        controller.streamChatPost(Map.of(
                "message", "查询热门商品",
                "requestId", "request-1",
                "sessionId", "session-a"), new MockHttpServletResponse());

        verify(routerClient).triggerRoutingDecision(
                eq("查询热门商品"), eq("42"), eq("request-1"));
        verify(userProfileService).prefetchForRequest(
                42L, "查询热门商品", "request-1");
        verify(userProfileService).commitAfterSuccessfulTurn(42L, "request-1");
        verify(routingCallLogService).saveLog(
                eq(42L), eq("session-a"), eq("request-1"), eq("查询热门商品"), eq("product_service"),
                eq("STREAM_ROUTER_SERVICE"), anyLong(), eq("SUCCESS"), eq("当前有 3 个热门商品"),
                eq(24L), eq(6L), eq(30L), eq("查询热门商品"), isNull());
    }

    @Test
    void completedMultiAgentTurnDoesNotRequireOrInventAgentName() throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("X-User-Id", "42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));

        when(routerClient.waitForDecisionFromRedis(eq("request-multi"), eq(60_000L), any(Runnable.class)))
                .thenReturn(Map.of(
                        "executionMode", "MULTI_AGENT",
                        "participatingAgents", List.of("product", "order"),
                        "workflowStatus", "COMPLETED",
                        "confidence", 0.8,
                        "intentTag", "product_order",
                        "result", "已查询商品并生成订单"));
        StreamChatController controller = new StreamChatController(
                routerClient, agentStreamClient, requestQueueService,
                routingCallLogService, null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.streamChatPost(Map.of(
                "message", "查询商品后创建订单",
                "requestId", "request-multi",
                "sessionId", "session-multi"), response);

        String rendered = response.getContentAsString();
        assertTrue(rendered.contains("\"sessionId\":\"session-multi\""));
        assertTrue(rendered.contains("\"requestId\":\"request-multi\""));
        assertTrue(rendered.contains("\"executionMode\":\"MULTI_AGENT\""));
        assertTrue(rendered.contains("\"participatingAgents\":[\"product\",\"order\"]"));
        assertTrue(rendered.contains("已查询商品并生成订单"));
        assertFalse(rendered.contains("\"agentName\""));
        assertFalse(rendered.contains("orchestrator"));
        verify(agentStreamClient, never()).isStreamingSupported(any());
    }

    @Test
    void longQuestionUsesHeavyDecisionBudget() {
        StreamChatController controller = new StreamChatController(
                routerClient, agentStreamClient, requestQueueService,
                routingCallLogService, null);
        String longQuestion = "长".repeat(160);

        assertEquals(60_000L, controller.decisionTimeoutFor("查询天气"));
        assertEquals(120_000L, controller.decisionTimeoutFor(longQuestion));
        assertEquals(90_000L, controller.sseIdleTimeoutFor("查询天气"));
        assertEquals(150_000L, controller.sseIdleTimeoutFor(longQuestion));
    }

    @Test
    void suspendsSecondSessionBeforeProfileOrRouterWorkStarts() throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("X-User-Id", "42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
        when(conversationGateService.acquire("42", "session-b", "request-b"))
                .thenReturn(new ConversationGateService.GateDecision(
                        ConversationGateService.GateStatus.SESSION_SUSPENDED,
                        "42", "session-b", "request-b", "session-a", 1, null));
        StreamChatController controller = new StreamChatController(
                routerClient, agentStreamClient, requestQueueService,
                routingCallLogService, null);
        ReflectionTestUtils.setField(controller, "userProfileService", userProfileService);
        ReflectionTestUtils.setField(controller, "conversationGateService", conversationGateService);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.streamChatPost(Map.of(
                "message", "查询商品",
                "requestId", "request-b",
                "sessionId", "session-b"), response);

        String rendered = response.getContentAsString();
        assertTrue(rendered.contains("conversation_suspended"));
        assertTrue(rendered.contains("USER_HAS_ACTIVE_CONVERSATION"));
        assertTrue(rendered.contains("session-a"));
        verify(userProfileService, never()).prefetchForRequest(any(), any(), any());
        verify(routerClient, never()).triggerRoutingDecision(any(), any(), any());
    }

    @Test
    void blocksDuplicateTurnWithoutSuspendingCurrentSession() throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("X-User-Id", "42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
        when(conversationGateService.acquire("42", "session-a", "request-b"))
                .thenReturn(new ConversationGateService.GateDecision(
                        ConversationGateService.GateStatus.REQUEST_BLOCKED,
                        "42", "session-a", "request-b", "session-a", 1, null));
        StreamChatController controller = new StreamChatController(
                routerClient, agentStreamClient, requestQueueService,
                routingCallLogService, null);
        ReflectionTestUtils.setField(controller, "userProfileService", userProfileService);
        ReflectionTestUtils.setField(controller, "conversationGateService", conversationGateService);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.streamChatPost(Map.of(
                "message", "再次查询商品",
                "requestId", "request-b",
                "sessionId", "session-a"), response);

        String rendered = response.getContentAsString();
        assertTrue(rendered.contains("request_blocked"));
        assertTrue(rendered.contains("SESSION_HAS_RUNNING_REQUEST"));
        assertFalse(rendered.contains("conversation_suspended"));
        verify(userProfileService, never()).prefetchForRequest(any(), any(), any());
        verify(routerClient, never()).triggerRoutingDecision(any(), any(), any());
    }

    @Test
    void cancelPropagatesToRouterUsingAuthenticatedOwner() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("X-User-Id", "42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
        when(routerClient.cancelRouting("request-cancel", "42")).thenReturn(true);
        StreamChatController controller = new StreamChatController(
                routerClient, agentStreamClient, requestQueueService,
                routingCallLogService, null);

        controller.cancelChat(Map.of("requestId", "request-cancel"));

        verify(requestQueueService).complete("request-cancel");
        verify(routerClient).cancelRouting("request-cancel", "42");
    }

    @Test
    void directAgentStreamCombinesUsageAndOwnsSingleTerminalEvent() throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("X-User-Id", "42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));

        AtomicReference<String> forwardedRequestId = new AtomicReference<>();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/stream", exchange -> {
            forwardedRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            byte[] body = ("""
                    event: response
                    data: {"type":"response","content":"streamed answer"}

                    event: token_usage
                    data: {"type":"token_usage","promptTokens":30,"completionTokens":10,"totalTokens":40}

                    event: done
                    data: {"type":"done"}

                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        try {
            when(routerClient.waitForDecisionFromRedis(eq("session-only"), eq(60_000L), any(Runnable.class)))
                    .thenReturn(Map.of(
                            "agentName", "product_service",
                            "confidence", 0.9,
                            "intentTag", "product",
                            "promptTokens", 20,
                            "completionTokens", 5,
                            "totalTokens", 25));
            when(agentStreamClient.isStreamingSupported("product_service")).thenReturn(true);
            when(agentStreamClient.getStreamUrl("product_service"))
                    .thenReturn("http://127.0.0.1:" + upstream.getAddress().getPort() + "/stream");
            when(requestQueueService.tryAcquireWithQueue("session-only", "session-only", 5))
                    .thenReturn(RequestQueueService.SlotResult.ACQUIRED);

            StreamChatController controller = new StreamChatController(
                    routerClient, agentStreamClient, requestQueueService,
                    routingCallLogService, null);
            MockHttpServletResponse response = new MockHttpServletResponse();

            controller.streamChatPost(Map.of(
                    "message", "recommend something",
                    "sessionId", "session-only"), response);

            String rendered = response.getContentAsString();
            int usageIndex = rendered.indexOf("event: token_usage");
            int doneIndex = rendered.indexOf("event: done");
            assertTrue(usageIndex >= 0 && doneIndex > usageIndex);
            assertEquals(doneIndex, rendered.lastIndexOf("event: done"));
            assertTrue(rendered.contains("\"promptTokens\":50"));
            assertTrue(rendered.contains("\"completionTokens\":15"));
            assertTrue(rendered.contains("\"totalTokens\":65"));
            assertEquals("session-only", forwardedRequestId.get());

            verify(routingCallLogService).saveLog(
                    eq(42L), eq("session-only"), eq("session-only"), eq("recommend something"),
                    eq("product_service"), eq("STREAM_ROUTER_SERVICE"), anyLong(),
                    eq("SUCCESS"), eq((String) null), eq(50L), eq(15L), eq(65L),
                    eq("recommend something"), isNull());
            verify(requestQueueService).complete("session-only");
        } finally {
            upstream.stop(0);
        }
    }
}
