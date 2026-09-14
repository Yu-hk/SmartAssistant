package com.example.smartassistant.consumer.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ChatDispatchRabbitConfigTest {
    private final ChatDispatchProperties properties = new ChatDispatchProperties(true, 30000, 150000, 4, 100);
    @Test void topologyHasFairShareQuorumBoundedQueueAndDurableDeadLetters() {
        var topology = new ChatDispatchRabbitConfig().chatDispatchTopology(properties);
        Queue queue = topology.getDeclarablesByType(Queue.class).stream()
                .filter(value -> value.getName().equals(ChatDispatchRabbitConfig.QUEUE)).findFirst().orElseThrow();
        assertTrue(queue.isDurable());
        assertEquals("quorum", queue.getArguments().get("x-queue-type"));
        assertEquals("reject-publish", queue.getArguments().get("x-overflow"));
        assertEquals(30000, queue.getArguments().get("x-message-ttl"));
        assertEquals("at-least-once", queue.getArguments().get("x-dead-letter-strategy"));
        assertEquals(ChatDispatchRabbitConfig.DLX, queue.getArguments().get("x-dead-letter-exchange"));
        assertFalse(queue.getArguments().containsKey("x-max-priority"), "Quorum uses its native two classes, not classic priorities");
    }
    @Test void listenerUsesManualAcknowledgementAndSmallPrefetch() {
        var factory = new ChatDispatchRabbitConfig().chatDispatchContainerFactory(mock(ConnectionFactory.class), properties);
        var container = factory.createListenerContainer();
        assertEquals(org.springframework.amqp.core.AcknowledgeMode.MANUAL, container.getAcknowledgeMode());
        assertEquals(1, org.springframework.test.util.ReflectionTestUtils.getField(container, "prefetchCount"));
        assertEquals(4, org.springframework.test.util.ReflectionTestUtils.getField(container, "concurrentConsumers"));
        assertFalse((Boolean) org.springframework.test.util.ReflectionTestUtils.getField(container, "defaultRequeueRejected"));
    }

    @Test void springWiresDispatchWithoutReplacingExistingRabbitTemplate() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class));
            context.registerBean(com.fasterxml.jackson.databind.ObjectMapper.class, () -> new com.fasterxml.jackson.databind.ObjectMapper());
            var existingTemplate = mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
            context.registerBean("rabbitTemplate", org.springframework.amqp.rabbit.core.RabbitTemplate.class, () -> existingTemplate);
            context.register(ChatDispatchRabbitConfig.class,
                    com.example.smartassistant.consumer.service.dispatch.ChatDispatchPublisher.class);
            context.refresh();
            assertNotNull(context.getBean(com.example.smartassistant.consumer.service.dispatch.ChatDispatchPublisher.class));
            assertSame(existingTemplate, context.getBean(org.springframework.amqp.rabbit.core.RabbitTemplate.class));
        }
    }
}
