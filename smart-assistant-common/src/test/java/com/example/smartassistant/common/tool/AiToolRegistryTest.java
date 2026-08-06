package com.example.smartassistant.common.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiToolRegistryTest {

    @Test
    void recursivelyFlattensNestedToolArraysAndCollections() {
        AiToolRegistry registry = new AiToolRegistry();

        var callbacks = registry.assemble(
                new SampleTools(),
                new Object[]{new SampleTools(), List.of(new SampleTools())});

        assertThat(callbacks).hasSize(3);
        assertThat(callbacks).allMatch(callback ->
                callback.getToolDefinition().name().equals("sampleTool"));
    }

    static class SampleTools {

        @Tool(description = "sample")
        String sampleTool() {
            return "ok";
        }
    }
}
