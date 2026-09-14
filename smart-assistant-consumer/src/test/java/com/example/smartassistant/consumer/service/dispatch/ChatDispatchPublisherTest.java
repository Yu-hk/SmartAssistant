package com.example.smartassistant.consumer.service.dispatch;

import com.example.smartassistant.consumer.service.sentiment.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatDispatchPublisherTest {
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final ChatDispatchPublisher publisher = new ChatDispatchPublisher(rabbit, new ObjectMapper());
    private ChatDispatchCommand command(TurnInsight insight) {
        return ChatDispatchCommand.create(42L, "session", "request", "查询订单", insight, System.currentTimeMillis() + 30000);
    }
    @Test void serverEmotionMapsToBrokerPriorityAndPersistentMessage() {
        var high = TurnInsight.analyzed(new SentimentAnalysisService.SentimentResult(4, "负面", "共情", false, false, 95), 1);
        doAnswer(call -> {
            Message message = call.getArgument(2);
            assertEquals(5, message.getMessageProperties().getPriority());
            assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getDeliveryMode());
            assertEquals("request", message.getMessageProperties().getMessageId());
            CorrelationData correlation = call.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        publisher.publish(command(high));
        verify(rabbit).setMandatory(true);
        assertEquals(0, command(TurnInsight.unknown("TIMEOUT", 750)).priority());
        assertEquals(0, command(null).priority());
    }
    @Test void nackAndReturnedMessageAreNotSuccessfulPublication() {
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "full"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThrows(IllegalStateException.class, () -> publisher.publish(command(null)));
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.setReturned(new ReturnedMessage(call.getArgument(2), 312, "NO_ROUTE", "exchange", "route"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThrows(IllegalStateException.class, () -> publisher.publish(command(null)));
    }
}
