package com.example.smartassistant.router.service.agent;

import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentCallerProductUriTest {

    @Test
    void productMessageIsEncodedExactlyOnce() {
        String question = "办公 笔记本电脑";
        var uri = AgentCallerService.buildProcessUri(
                "http://product:8084", "product", question);

        String rawValue = uri.getRawQuery().substring("message=".length());
        assertEquals(question, URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
        assertFalse(rawValue.contains("%25E6"));
    }
}
