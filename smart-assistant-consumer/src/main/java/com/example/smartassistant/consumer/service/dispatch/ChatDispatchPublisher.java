package com.example.smartassistant.consumer.service.dispatch;

import com.example.smartassistant.consumer.config.ChatDispatchRabbitConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class ChatDispatchPublisher {
    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired
    public ChatDispatchPublisher(ConnectionFactory connectionFactory, ObjectMapper mapper) {
        // Private template avoids replacing Boot's existing RabbitTemplate used by profile/recovery messages.
        this(new RabbitTemplate(connectionFactory), mapper);
    }
    ChatDispatchPublisher(RabbitTemplate rabbit, ObjectMapper mapper) {
        this.rabbit = rabbit; this.mapper = mapper;
        this.rabbit.setMandatory(true);
    }
    public void publish(ChatDispatchCommand command) {
        try {
            var message = MessageBuilder.withBody(mapper.writeValueAsBytes(command))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON).setContentEncoding("UTF-8")
                    .setMessageId(command.requestId()).setPriority(command.priority())
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
            var correlation = new CorrelationData(command.requestId() + ":" + UUID.randomUUID());
            rabbit.send(ChatDispatchRabbitConfig.EXCHANGE, ChatDispatchRabbitConfig.ROUTING_KEY, message, correlation);
            var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) throw new IllegalStateException("Dispatch not accepted by broker");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Dispatch confirmation interrupted", error);
        } catch (Exception error) {
            throw new IllegalStateException("Dispatch confirmation unavailable", error);
        }
    }
}
