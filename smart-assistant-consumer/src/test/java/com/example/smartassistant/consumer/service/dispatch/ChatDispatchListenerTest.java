package com.example.smartassistant.consumer.service.dispatch;

import com.example.smartassistant.consumer.client.RouterClient;
import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
import com.example.smartassistant.routing.contract.RoutingKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatDispatchListenerTest {
    private final ChatDispatchStore store = mock(ChatDispatchStore.class);
    private final RouterClient router = mock(RouterClient.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final UserProfileService profiles = mock(UserProfileService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Channel channel = mock(Channel.class);
    private final ChatDispatchListener listener = new ChatDispatchListener(store, router, mapper, redis, profiles);
    private final ChatDispatchCommand command = new ChatDispatchCommand(42L, "session", "request", "查订单", false, 5, Long.MAX_VALUE);
    private Message message() throws Exception {
        return MessageBuilder.withBody(mapper.writeValueAsBytes(command)).setDeliveryTag(1L).build();
    }
    private void claimed() {
        when(store.start(eq(command), anyLong())).thenReturn("ACQUIRED");
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(store.finish(eq(command), eq("RUNNING"), anyString(), anyMap())).thenReturn(true);
    }

    @Test void resultWithTelemetryIsPersistedBeforeAckAndRunsOnce() throws Exception {
        claimed();
        var result = Map.<String, Object>of("result", "已查询", "totalTokens", 123, "toolCalls", java.util.List.of(Map.of("name", "queryOrder")));
        when(router.callRouterRaw("查订单", "42", "session", "request", false)).thenReturn(result);
        listener.receive(message(), channel);
        var order = inOrder(store, router, channel);
        order.verify(store).start(eq(command), anyLong());
        order.verify(router).callRouterRaw("查订单", "42", "session", "request", false);
        order.verify(store).finish(command, "RUNNING", "COMPLETED", result);
        order.verify(channel).basicAck(1L, false);
        verifyNoInteractions(profiles);
    }
    @Test void redeliveryOfRunningAttemptIsDeadLetteredNotReexecuted() throws Exception {
        when(store.start(eq(command), anyLong())).thenReturn("RUNNING");
        listener.receive(message(), channel);
        verify(channel).basicReject(1, false);
        verifyNoInteractions(router, profiles);
    }
    @Test void completedAndCancelledDuplicatesAreAckedWithoutExecuting() throws Exception {
        for (String status : java.util.List.of("COMPLETED", "CANCELLED", "REJECTED")) {
            when(store.start(eq(command), anyLong())).thenReturn(status);
            listener.receive(message(), channel);
        }
        verify(channel, times(3)).basicAck(1, false);
        verifyNoInteractions(router, profiles);
    }
    @Test void expiredRequestDoesNotReachRouter() throws Exception {
        when(store.start(eq(command), anyLong())).thenReturn("EXPIRED");
        listener.receive(message(), channel);
        verify(store).finish(eq(command), eq("QUEUED"), eq("EXPIRED"), anyMap());
        verify(channel).basicReject(1, false);
        verifyNoInteractions(router);
    }
    @Test void cancellationSignalStopsBeforeAnyBusinessCall() throws Exception {
        claimed();
        when(redis.opsForValue().get(RoutingKeys.cancellation("request"))).thenReturn("42");
        listener.receive(message(), channel);
        verify(store).finish(command, "RUNNING", "COMPLETED", PriorityRoutingDispatcher.cancelled());
        verify(channel).basicAck(1, false);
        verifyNoInteractions(router);
    }
    @Test void transportErrorIsUncertainAndNotRetried() throws Exception {
        claimed();
        var failure = Map.<String, Object>of("result", "结果未知", "error", "read timeout");
        when(router.callRouterRaw(any(), any(), any(), any(), anyBoolean())).thenReturn(failure);
        listener.receive(message(), channel);
        verify(store).finish(eq(command), eq("RUNNING"), eq("UNCERTAIN"), argThat(result ->
                "ROUTER_EXECUTION_UNCONFIRMED".equals(result.get("error")) && !result.toString().contains("read timeout")));
        verify(channel).basicReject(1, false);
        verify(router, times(1)).callRouterRaw(any(), any(), any(), any(), anyBoolean());
    }
    @Test void provenUnsentFailureIsFailedNotUncertainOrSuccessfulAndNeverReplayed() throws Exception {
        claimed();
        var failure = PriorityRoutingDispatcher.failure("ROUTER_REQUEST_NOT_SENT", "本轮尚未开始");
        when(router.callRouterRaw(any(), any(), any(), any(), anyBoolean())).thenReturn(failure);
        listener.receive(message(), channel);
        verify(store).finish(command, "RUNNING", "FAILED", failure);
        verify(channel).basicReject(1, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        when(store.start(eq(command), anyLong())).thenReturn("FAILED");
        listener.receive(message(), channel);
        verify(router, times(1)).callRouterRaw(any(), any(), any(), any(), anyBoolean());
    }
    @Test void queuedRequestNeverRestartsProfilePreparation() throws Exception {
        claimed();
        when(router.callRouterRaw(any(), any(), any(), any(), anyBoolean())).thenReturn(Map.of("result", "完成"));
        listener.receive(message(), channel);
        verifyNoInteractions(profiles);
        verify(router).callRouterRaw("查订单", "42", "session", "request", false);
        verify(redis, never()).hasKey(anyString());
    }
    @Test void unavailableProfileSubsystemDoesNotDeadLetterBusinessRequest() throws Exception {
        claimed();
        when(profiles.prefetchForRequest(any(), any(), any())).thenThrow(new IllegalStateException("profile unavailable"));
        var result = Map.<String, Object>of("result", "订单查询完成");
        when(router.callRouterRaw(any(), any(), any(), any(), anyBoolean())).thenReturn(result);
        listener.receive(message(), channel);
        verify(store).finish(command, "RUNNING", "COMPLETED", result);
        verify(channel).basicAck(1, false);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }
    @Test void redisFailurePreventsExecutionAndCannotBeAckedAsSuccess() throws Exception {
        when(store.start(eq(command), anyLong())).thenThrow(new IllegalStateException("unavailable"));
        listener.receive(message(), channel);
        verify(channel).basicReject(1, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNoInteractions(router);
    }
}
