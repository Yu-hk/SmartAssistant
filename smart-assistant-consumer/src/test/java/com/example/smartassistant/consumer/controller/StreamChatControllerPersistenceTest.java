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

    @Test
    void sendsStructuredMissingInformationAlongsideNormalReply() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(preprocessingService.prepare(42L, "owned", "form", "推荐便携笔记本"))
                .thenReturn(com.example.smartassistant.consumer.service.sentiment.TurnInsight.unknown("TIMEOUT", 750));
        when(routerClient.waitForDecisionFromRedis(eq("form"), eq(60_000L), any(Runnable.class)))
                .thenReturn(Map.of("agentName", "product", "result", "请补充可接受的重量上限。",
                        "workflowStatus", "CLARIFICATION"));
        StreamChatController controller = new StreamChatController(routerClient, agentStreamClient,
                requestQueueService, routingCallLogService, null, preprocessingService);
        var forms = org.mockito.Mockito.mock(com.example.smartassistant.consumer.service.core.ClarificationService.class);
        when(forms.issue(eq("42"), eq("owned"), eq("form"), any(), eq("CLARIFICATION")))
                .thenReturn(new com.example.smartassistant.consumer.service.core.ClarificationService.Issued(
                        new com.example.smartassistant.consumer.service.core.ClarificationService.Form(2, "signed", 9999999999999L,
                                List.of(com.example.smartassistant.consumer.service.core.ClarificationPolicy.field("weight"))),
                        new com.example.smartassistant.consumer.service.infrastructure.TokenUsageExtractor.TokenUsage(1L, 1L, 2L)));
        ReflectionTestUtils.setField(controller, "clarificationService", forms);
        var response = new MockHttpServletResponse();
        controller.streamChatPost(Map.of("message", "推荐便携笔记本", "requestId", "form", "sessionId", "owned"), response);
        String events = response.getContentAsString();
        assertTrue(events.contains("\"clarificationForm\":{\"version\":2"));
        assertTrue(events.contains("\"key\":\"weight\""));
        assertTrue(events.contains("event: done"));
    }

    @Test void invalidStructuredSubmissionNeverDispatchesBusiness() throws Exception {
        var request = new MockHttpServletRequest(); request.addHeader("X-User-Id", "42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var controller = new StreamChatController(routerClient, agentStreamClient,
                requestQueueService, routingCallLogService, null, preprocessingService);
        var forms = org.mockito.Mockito.mock(com.example.smartassistant.consumer.service.core.ClarificationService.class);
        ReflectionTestUtils.setField(controller, "clarificationService", forms);
        when(forms.accept(eq("42"), eq("owned"), any())).thenThrow(new IllegalArgumentException("表单已失效"));
        var response = new MockHttpServletResponse();
        controller.streamChatPost(Map.of("message", "确认下单", "requestId", "attempt", "sessionId", "owned",
                "clarification", Map.of("token", "tampered", "values", Map.of("weight", "-1"))), response);
        assertEquals(422, response.getStatus());
        org.mockito.Mockito.verifyNoInteractions(routerClient, preprocessingService, routingCallLogService);
    }

    @Test void formSubmissionRequiresExistingActiveSessionBeforeModelOrBusiness() throws Exception {
        var request = new MockHttpServletRequest(); request.addHeader("X-User-Id", "42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var controller = new StreamChatController(routerClient, agentStreamClient, requestQueueService, routingCallLogService, null, preprocessingService);
        ReflectionTestUtils.setField(controller, "conversationGateService", conversationGateService);
        when(conversationGateService.acquireExisting("42", "closed", "attempt"))
                .thenReturn(new ConversationGateService.GateDecision(ConversationGateService.GateStatus.SESSION_CLOSED,
                        "42", "closed", "attempt", null, 0, null));
        var response = new MockHttpServletResponse();
        controller.streamChatPost(Map.of("message", "补充信息", "requestId", "attempt", "sessionId", "closed",
                "clarification", Map.of("token", "old", "values", Map.of("weight", "3"))), response);
        assertEquals(422, response.getStatus());
        org.mockito.Mockito.verifyNoInteractions(routerClient, preprocessingService, routingCallLogService);
        verify(conversationGateService, never()).acquire(any(), any(), any());
    }

    @Mock private RouterClient routerClient;
    @Mock private AgentStreamClient agentStreamClient;
    @Mock private RequestQueueService requestQueueService;
    @Mock private RoutingCallLogService routingCallLogService;
    @Mock private UserProfileService userProfileService;
    @Mock private ConversationGateService conversationGateService;
    @Mock private com.example.smartassistant.consumer.service.core.ConversationPreprocessingService preprocessingService;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void productionSseDispatchesThroughMqAndDoesNotTriggerDirectRouter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var dispatcher = org.mockito.Mockito.mock(com.example.smartassistant.consumer.service.dispatch.PriorityRoutingDispatcher.class);
        var insight = com.example.smartassistant.consumer.service.sentiment.TurnInsight.unknown("TIMEOUT", 750);
        when(preprocessingService.prepare(42L, "owned", "mq", "查询订单")).thenReturn(insight);
        when(dispatcher.enabled()).thenReturn(true);
        when(dispatcher.route(eq("查询订单"), eq("42"), eq("owned"), eq("mq"), eq(insight), eq(60000L), any(Runnable.class)))
                .thenReturn(Map.of("result", "查询完成", "agentName", "order", "totalTokens", 99));
        StreamChatController controller = new StreamChatController(routerClient, agentStreamClient,
                requestQueueService, routingCallLogService, null, preprocessingService);
        ReflectionTestUtils.setField(controller, "priorityDispatcher", dispatcher);
        var response = new MockHttpServletResponse();
        controller.streamChatPost(Map.of("message", "查询订单", "requestId", "mq", "sessionId", "owned", "priority", "999"), response);
        String events = response.getContentAsString();
        assertTrue(events.contains("event: queue"));
        assertTrue(events.contains("\"totalTokens\":99"));
        assertTrue(events.contains("event: done"));
        org.mockito.Mockito.verifyNoInteractions(routerClient, requestQueueService, agentStreamClient);
    }

    @Test
    void productionSseUsesSharedPreprocessingBeforeRoutingWithoutChangingQuestion() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var insight = com.example.smartassistant.consumer.service.sentiment.TurnInsight.analyzed(
                new com.example.smartassistant.consumer.service.sentiment.SentimentAnalysisService.SentimentResult(
                        4, "负面", "共情", false, false, 95), 2);
        when(preprocessingService.prepare(42L, "owned", "sentiment", "太慢了，查询订单"))
                .thenReturn(insight);
        when(routerClient.waitForDecisionFromRedis(eq("sentiment"), eq(60_000L), any(Runnable.class)))
                .thenReturn(Map.of("agentName", "order", "result", "请提供订单号。"));
        StreamChatController controller = new StreamChatController(routerClient, agentStreamClient,
                requestQueueService, routingCallLogService, null, preprocessingService);
        ReflectionTestUtils.setField(controller, "userProfileService", userProfileService);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.streamChatPost(Map.of("message", "太慢了，查询订单", "requestId", "sentiment", "sessionId", "owned"), response);

        String events = response.getContentAsString();
        assertTrue(events.contains("event: sentiment"));
        assertTrue(events.contains("\"suggestedPriority\":\"ELEVATED\""));
        assertTrue(events.indexOf("event: sentiment") < events.indexOf("event: routed"));
        assertTrue(events.contains("抱歉给您带来不便。请提供订单号。"));
        assertTrue(events.contains("event: done"));
        assertFalse(events.contains("正在为您转接"));
        var order = org.mockito.Mockito.inOrder(preprocessingService, routerClient);
        order.verify(preprocessingService).prepare(42L, "owned", "sentiment", "太慢了，查询订单");
        order.verify(routerClient).triggerRoutingDecision("太慢了，查询订单", "42", "sentiment", "owned", false);
        verify(userProfileService, never()).prefetchForRequest(any(), any(), any());
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
