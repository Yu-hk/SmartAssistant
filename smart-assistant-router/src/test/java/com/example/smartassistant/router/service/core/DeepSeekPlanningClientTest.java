package com.example.smartassistant.router.service.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeepSeekPlanningClientTest {

    @Test
    void directHttpUsageIsAttributedThroughTheRealAsyncGatewayAndAccumulates() {
        var gateway = new com.example.smartassistant.common.gateway.llm.AgentLLMGateway();
        String requestId = "planner-usage-test";
        org.slf4j.MDC.put("requestId", requestId);
        try {
            for (int i = 0; i < 2; i++) {
                var result = gateway.call(() -> DeepSeekPlanningClient.extractMeasuredContent(
                        "{\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120},"
                                + "\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                        new ObjectMapper(), org.slf4j.MDC.get("requestId")),
                        "planner-test", com.example.smartassistant.common.gateway.llm.LLMCallConfig.simple());
                assertEquals("ok", result.content());
            }
            assertEquals(new com.example.smartassistant.common.audit.TokenUsageCache.TokenUsage(200L, 40L, 240L),
                    com.example.smartassistant.common.audit.TokenUsageCache.consume(requestId));
        } finally {
            org.slf4j.MDC.clear();
            com.example.smartassistant.common.audit.TokenUsageCache.consume(requestId);
        }
    }

    @Test
    void missingProviderUsageIsNotInventedAsZero() {
        String id = "planner-missing-test";
        DeepSeekPlanningClient.extractMeasuredContent("{\"choices\":[]}", new ObjectMapper(), id);
        com.example.smartassistant.common.audit.TokenUsageCache.record(id, 10, 2, 12);
        var usage = com.example.smartassistant.common.audit.TokenUsageCache.consume(id);
        org.junit.jupiter.api.Assertions.assertNull(usage);
    }

    @Test
    void disablesThinkingAndExtractsFinalContent() {
        Map<String, Object> body = DeepSeekPlanningClient.requestBody(
                "deepseek-v4-flash", "plan", 2048);

        assertEquals(Map.of("type", "disabled"), body.get("thinking"));
        assertEquals(2048, body.get("max_tokens"));

        Map<String, Object> structured = DeepSeekPlanningClient.requestBody(
                "deepseek-v4-pro", "system", "user", 1024);
        assertEquals(List.of(
                        Map.of("role", "system", "content", "system"),
                        Map.of("role", "user", "content", "user")),
                structured.get("messages"));

        String raw = "{\"choices\":[{\"message\":{\"reasoning_content\":null,"
                + "\"content\":\"t1|查询|product_agent|none|返回结果\"}}]}";
        assertEquals("t1|查询|product_agent|none|返回结果",
                DeepSeekPlanningClient.extractContent(raw, new ObjectMapper()));
    }
}
