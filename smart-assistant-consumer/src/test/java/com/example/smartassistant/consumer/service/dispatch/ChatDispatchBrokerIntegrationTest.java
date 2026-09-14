package com.example.smartassistant.consumer.service.dispatch;

import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.AMQP;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies actual RabbitMQ 4.1 delivery, not just an integer on a mocked message. */
@EnabledIfEnvironmentVariable(named = "CHAT_DISPATCH_TEST_RABBIT_PORT", matches = "\\d+")
class ChatDispatchBrokerIntegrationTest {
    @Test void quorumFavoursHighPriorityWhileNormalRequestsStillProgress() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(System.getenv().getOrDefault("CHAT_DISPATCH_TEST_RABBIT_HOST", "127.0.0.1"));
        factory.setPort(Integer.parseInt(System.getenv("CHAT_DISPATCH_TEST_RABBIT_PORT")));
        factory.setUsername(System.getenv().getOrDefault("CHAT_DISPATCH_TEST_RABBIT_USERNAME", "guest"));
        factory.setPassword(System.getenv().getOrDefault("CHAT_DISPATCH_TEST_RABBIT_PASSWORD", "guest"));
        String queue = "test.chat-priority." + UUID.randomUUID();
        try (var connection = factory.newConnection(); var publisher = connection.createChannel(); var consumer = connection.createChannel()) {
            String version = String.valueOf(connection.getServerProperties().get("version"));
            assertTrue(version.matches("4\\.[012]\\..*"), "Fair-share contract requires RabbitMQ 4.0–4.2; found " + version);
            publisher.queueDeclare(queue, true, false, false, Map.of("x-queue-type", "quorum", "x-expires", 600000));
            try {
                publisher.confirmSelect();
                consumer.basicQos(1);
                var received = new LinkedBlockingQueue<com.rabbitmq.client.Delivery>();
                String consumerTag = consumer.basicConsume(queue, false, (tag, delivery) -> received.add(delivery), tag -> {});
                publisher.basicPublish("", queue, props(0), "blocker".getBytes(StandardCharsets.UTF_8));
                publisher.waitForConfirmsOrDie(5000);
                var blocker = received.poll(5, TimeUnit.SECONDS);
                assertNotNull(blocker);
                // Prefetch=1 holds delivery until all normal and high-priority messages are queued.
                for (int i = 0; i < 6; i++) publisher.basicPublish("", queue, props(0), ("normal-" + i).getBytes(StandardCharsets.UTF_8));
                for (int i = 0; i < 6; i++) publisher.basicPublish("", queue, props(5), ("high-" + i).getBytes(StandardCharsets.UTF_8));
                publisher.waitForConfirmsOrDie(5000);
                consumer.basicAck(blocker.getEnvelope().getDeliveryTag(), false);
                List<String> firstSix = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    var delivery = received.poll(5, TimeUnit.SECONDS);
                    assertNotNull(delivery);
                    firstSix.add(new String(delivery.getBody(), StandardCharsets.UTF_8));
                    consumer.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                }
                assertTrue(firstSix.stream().filter(value -> value.startsWith("high")).count() >= 3, firstSix.toString());
                assertTrue(firstSix.stream().anyMatch(value -> value.startsWith("normal")), firstSix.toString());
                consumer.basicCancel(consumerTag);
            } finally { publisher.queueDelete(queue); }
        }
    }
    private static AMQP.BasicProperties props(int priority) {
        return new AMQP.BasicProperties.Builder().deliveryMode(2).priority(priority).build();
    }
}
