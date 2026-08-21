package com.example.smartassistant.common.model.tier;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.ChatOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatOptionsMergeTest {

    @Test
    void requestLimitsOverrideDefaultsWhileTierModelIsPreserved() {
        ChatOptions defaults = ChatOptions.builder()
                .model("qwen3.7-flash")
                .temperature(0.5)
                .build();
        ChatOptions request = ChatOptions.builder()
                .maxTokens(1024)
                .temperature(0.1)
                .build();

        ChatOptions merged = ChatOptionsMerge.merge(defaults, request);

        assertEquals("qwen3.7-flash", merged.getModel());
        assertEquals(1024, merged.getMaxTokens());
        assertEquals(0.1, merged.getTemperature());
    }
}
