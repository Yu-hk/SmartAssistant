package com.example.smartassistant.common.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MemoryExtractorContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MemoryExtractor.class)
            .withBean(AgentMemoryService.class, () -> mock(AgentMemoryService.class));

    @Test
    void doesNotLoadInServicesWithoutLightChatModel() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(MemoryExtractor.class));
    }

    @Test
    void loadsWhenLightChatModelIsAvailable() {
        contextRunner
                .withBean("lightChatModel", ChatModel.class, () -> mock(ChatModel.class))
                .run(context -> assertThat(context).hasSingleBean(MemoryExtractor.class));
    }
}
