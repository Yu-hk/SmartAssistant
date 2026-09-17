package com.example.smartassistant.consumer.service.dispatch;

import com.example.smartassistant.consumer.client.RouterClient;
import com.example.smartassistant.consumer.config.ChatDispatchRabbitConfig;
import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
import com.example.smartassistant.routing.contract.RoutingKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import static com.example.smartassistant.common.error.CustomerMessages.*;

@Service
public class ChatDispatchListener {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChatDispatchListener.class);
    private final ChatDispatchStore store;
    private final RouterClient router;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;
    private final UserProfileService profiles;
    public ChatDispatchListener(ChatDispatchStore store, RouterClient router, ObjectMapper mapper,
                                StringRedisTemplate redis, UserProfileService profiles) {
        this.store = store; this.router = router; this.mapper = mapper; this.redis = redis; this.profiles = profiles;
    }

    @RabbitListener(queues = ChatDispatchRabbitConfig.QUEUE, containerFactory = "chatDispatchContainerFactory",
            autoStartup = "${chat.dispatch.enabled:true}")
    public void receive(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        ChatDispatchCommand command = null;
        try {
            command = mapper.readValue(message.getBody(), ChatDispatchCommand.class);
            String claim = store.start(command, System.currentTimeMillis());
            if ("EXPIRED".equals(claim)) {
                store.finish(command, "QUEUED", "EXPIRED", PriorityRoutingDispatcher.failure("QUEUE_TIMEOUT", QUEUE_EXPIRED));
                channel.basicReject(tag, false);
                return;
            }
            if (!"ACQUIRED".equals(claim)) {
                // A crash after RUNNING is ambiguous, not permission to retry an order/tool operation.
                if ("RUNNING".equals(claim) || "MISSING".equals(claim) || "CONFLICT".equals(claim) || "UNCERTAIN".equals(claim)) {
                    channel.basicReject(tag, false);
                } else channel.basicAck(tag, false);
                return;
            }
            Map<String, Object> result;
            if (command.userId().toString().equals(redis.opsForValue().get(RoutingKeys.cancellation(command.requestId())))) {
                result = PriorityRoutingDispatcher.cancelled();
            } else {
                // Optional preparation is deduplicated and contains no caller-thread storage I/O.
                try {
                    profiles.prefetchForRequest(command.userId(), command.question(), command.requestId());
                } catch (RuntimeException unavailable) {
                    log.warn("[ChatDispatch] Optional profile skipped: requestId={}", command.requestId());
                }
                result = router.callRouterRaw(command.question(), command.userId().toString(), command.sessionId(),
                        command.requestId(), command.allowAnswerCache());
                if (result != null && "CANCELLED".equals(result.get("workflowStatus"))) {
                    result = PriorityRoutingDispatcher.cancelled();
                } else if (result == null || !(result.get("result") instanceof String text) || text.isBlank()) {
                    result = PriorityRoutingDispatcher.failure("INVALID_ROUTER_RESULT", UNCONFIRMED);
                }
            }
            boolean notSent = "ROUTER_REQUEST_NOT_SENT".equals(result.get("error"));
            boolean uncertain = result.get("error") != null && !notSent;
            if (uncertain) result = PriorityRoutingDispatcher.failure("ROUTER_EXECUTION_UNCONFIRMED",
                    UNCONFIRMED);
            if (!store.finish(command, "RUNNING", notSent ? "FAILED" : uncertain ? "UNCERTAIN" : "COMPLETED", result)) {
                throw new IllegalStateException("Dispatch completion could not be recorded");
            }
            if (uncertain || notSent) channel.basicReject(tag, false);
            else channel.basicAck(tag, false);
        } catch (Exception error) {
            // Do not log body, credentials or raw exception text. Unacked business work is not auto-replayed.
            log.error("[ChatDispatch] Delivery requires inspection: requestId={}, errorType={}",
                    command != null ? command.requestId() : message.getMessageProperties().getMessageId(), error.getClass().getSimpleName());
            channel.basicReject(tag, false);
        }
    }
}
