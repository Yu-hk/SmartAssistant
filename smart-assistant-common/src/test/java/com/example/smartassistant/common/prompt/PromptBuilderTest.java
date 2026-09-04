package com.example.smartassistant.common.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBuilderTest {

    @Test
    void assemblesBaseAndServicePrompt() {
        String result = PromptBuilder.build()
                .withServicePrompt("你是商品助手。")
                .assemble();

        assertThat(result).contains("你是商品助手。");
        assertThat(result).doesNotContain("null");
    }

    @Test
    void acceptsMissingServicePrompt() {
        assertThat(PromptBuilder.build().assemble()).isNotNull();
    }
}
