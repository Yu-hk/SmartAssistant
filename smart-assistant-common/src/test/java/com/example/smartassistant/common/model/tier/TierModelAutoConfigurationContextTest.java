package com.example.smartassistant.common.model.tier;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TierModelAutoConfigurationContextTest {

    @Test
    void createsTierRouterWithoutDependingOnBeanDefinitionOrder() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton(
                    "providerChatModel",
                    mock(ChatModel.class));
            context.getBeanFactory().registerSingleton(
                    "meterRegistry",
                    new SimpleMeterRegistry());
            context.register(TierModelAutoConfiguration.class);

            context.refresh();

            assertThat(context.getBean(TieredModelRouter.class)).isNotNull();
        }
    }
}
