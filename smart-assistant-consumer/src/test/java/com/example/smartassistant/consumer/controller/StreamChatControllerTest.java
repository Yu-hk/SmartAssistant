package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.client.AgentStreamClient;
import com.example.smartassistant.consumer.client.RouterClient;
import com.example.smartassistant.consumer.service.core.RequestQueueService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamChatControllerTest {

    @Test
    @DisplayName("仅支持同步调用的 Agent 结果应适配为 text + done SSE 事件")
    void adaptsSynchronousAgentResultToSse() throws Exception {
        RouterClient routerClient = mock(RouterClient.class);
        AgentStreamClient agentStreamClient = mock(AgentStreamClient.class);
        RequestQueueService requestQueueService = mock(RequestQueueService.class);
        StreamChatController controller = new StreamChatController(
                routerClient, agentStreamClient, requestQueueService, null);

        String requestId = "req-order-1";
        String answer = "最近一笔订单已发货，物流状态为运输中。";
        when(routerClient.triggerRoutingDecision(
                "帮我查询最近一笔订单的物流状态", "anonymous", requestId))
                .thenReturn(Map.of(
                        "agentName", "order_agent",
                        "confidence", 0.98d,
                        "intentTag", "order_logistics_query",
                        "result", answer));
        // Even if discovery incorrectly advertises an SSE URL, the synchronous result
        // returned by /route must win so the Agent is not invoked a second time.
        when(agentStreamClient.isStreamingSupported("order_agent")).thenReturn(true);

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.streamChat(
                "帮我查询最近一笔订单的物流状态",
                requestId,
                requestId,
                true,
                RequestQueueService.PRIORITY_NORMAL,
                null,
                response);

        String body = response.getContentAsString();
        assertTrue(body.contains("event: text"));
        assertTrue(body.contains(answer));
        assertTrue(body.contains("event: done"));
        assertFalse(body.contains("Agent 不支持流式响应"));
        verify(agentStreamClient, never()).isStreamingSupported("order_agent");
        verify(agentStreamClient, never()).getStreamUrl("order_agent");
    }
}
