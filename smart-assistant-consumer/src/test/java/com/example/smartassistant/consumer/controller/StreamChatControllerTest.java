package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.client.AgentStreamClient;
import com.example.smartassistant.consumer.client.RouterClient;
import com.example.smartassistant.consumer.service.core.RequestQueueService;
import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
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
    @DisplayName("A synchronous Agent result is adapted to text and done SSE events")
    void adaptsSynchronousAgentResultToSse() throws Exception {
        RouterClient routerClient = mock(RouterClient.class);
        AgentStreamClient agentStreamClient = mock(AgentStreamClient.class);
        RequestQueueService requestQueueService = mock(RequestQueueService.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        StreamChatController controller = new StreamChatController(
                routerClient, agentStreamClient, requestQueueService, userProfileService, null);

        String requestId = "req-order-1";
        String question = "Check the latest order logistics status";
        String answer = "The latest order is in transit.";
        when(routerClient.triggerRoutingDecision(question, "42", requestId))
                .thenReturn(Map.of(
                        "agentName", "order_agent",
                        "confidence", 0.98d,
                        "intentTag", "order_logistics_query",
                        "result", answer));
        // The synchronous result returned by /route must win so the Agent is not
        // invoked a second time, even if discovery advertises an SSE URL.
        when(agentStreamClient.isStreamingSupported("order_agent")).thenReturn(true);

        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.streamChat(
                question,
                requestId,
                requestId,
                true,
                RequestQueueService.PRIORITY_NORMAL,
                "42",
                null,
                response);

        String body = response.getContentAsString();
        assertTrue(body.contains("event: text"));
        assertTrue(body.contains(answer));
        assertTrue(body.contains("event: done"));
        assertFalse(body.contains("does not support streaming"));
        verify(agentStreamClient, never()).isStreamingSupported("order_agent");
        verify(agentStreamClient, never()).getStreamUrl("order_agent");
        verify(userProfileService).captureLatentSignals(42L, question);
    }
}
