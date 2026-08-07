package com.example.smartassistant.common.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolPromptFormatterTest {

    @Test
    void rendersDescriptionsFromActualCallbacks() {
        String prompt = ToolPromptFormatter.appendRuntimeTools("base", List.of(
                callback("queryWeather", "查询指定城市的实时天气"),
                callback("searchWeb", "联网搜索具体信息")));

        assertTrue(prompt.contains("queryWeather: 查询指定城市的实时天气"));
        assertTrue(prompt.contains("searchWeb: 联网搜索具体信息"));
        assertFalse(prompt.contains("discover_tools(capabilityQuery="));
    }

    @Test
    void onlyAddsDiscoveryGuidanceWhenCallbackIsPresent() {
        String prompt = ToolPromptFormatter.appendRuntimeTools("base", List.of(
                callback("discover_tools", "发现并加载额外能力")));

        assertTrue(prompt.contains("discover_tools(capabilityQuery=能力名)"));
    }

    private static ToolCallback callback(String name, String description) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(definition.description()).thenReturn(description);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }
}
