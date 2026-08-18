package com.example.smartassistant.router.service.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeepSeekPlanningClientTest {

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
