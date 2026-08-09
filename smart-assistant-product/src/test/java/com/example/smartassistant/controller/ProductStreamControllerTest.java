package com.example.smartassistant.controller;

import com.example.smartassistant.common.audit.TokenUsageCache;
import com.example.smartassistant.common.audit.TokenUsageHeaders;
import com.example.smartassistant.common.quality.DomainAgentResponse;
import com.example.smartassistant.common.quality.DomainQualityHeaders;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.service.agent.StreamingProductAgentService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductStreamControllerTest {

    @Test
    void streamEmitsMeasuredUsageBeforeDone() {
        StreamingProductAgentService service = mock(StreamingProductAgentService.class);
        when(service.execute("耳机推荐")).thenAnswer(ignored -> {
            TokenUsageCache.record("req-stream", 18, 7, 25);
            return "推荐降噪耳机";
        });
        ProductStreamController controller = new ProductStreamController(service);

        List<org.springframework.http.codec.ServerSentEvent<String>> events =
                controller.streamChat("耳机推荐", false, "req-stream")
                        .collectList().block();

        assertEquals(List.of("tool_call", "tool_result", "response", "token_usage", "done"),
                events.stream().map(org.springframework.http.codec.ServerSentEvent::event).toList());
        assertEquals("{\"type\":\"token_usage\",\"promptTokens\":18,"
                        + "\"completionTokens\":7,\"totalTokens\":25}",
                events.get(3).data());
        assertNull(TokenUsageCache.consume("req-stream"));
    }

    @Test
    void syncEndpointKeepsPlainTextBodyAndAddsQualityHeaders() {
        StreamingProductAgentService service = mock(StreamingProductAgentService.class);
        when(service.executeWithQuality("推荐办公电脑", "req-1"))
                .thenReturn(DomainAgentResponse.of(
                        "建议选择 16GB 内存的办公电脑。",
                        DomainQualityResult.pass(0.9, "PRODUCT_FACTS_VERIFIED")));
        ProductStreamController controller = new ProductStreamController(service);
        TokenUsageCache.record("req-1", 24, 11, 35);

        var response = controller.chatSync("推荐办公电脑", "req-1");

        assertEquals("建议选择 16GB 内存的办公电脑。", response.getBody());
        assertEquals("PASS", response.getHeaders().getFirst(DomainQualityHeaders.STATUS));
        assertEquals("0.9", response.getHeaders().getFirst(DomainQualityHeaders.SCORE));
        assertEquals("PRODUCT_FACTS_VERIFIED",
                response.getHeaders().getFirst(DomainQualityHeaders.REASON_CODES));
        assertEquals("24", response.getHeaders().getFirst(TokenUsageHeaders.PROMPT_TOKENS));
        assertEquals("11", response.getHeaders().getFirst(TokenUsageHeaders.COMPLETION_TOKENS));
        assertEquals("35", response.getHeaders().getFirst(TokenUsageHeaders.TOTAL_TOKENS));
        verify(service).executeWithQuality("推荐办公电脑", "req-1");
    }
}
