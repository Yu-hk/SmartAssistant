package com.example.smartassistant.router.service.recovery;

import com.example.smartassistant.router.config.WorkflowRecoveryRabbitProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.ChannelProxy;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** RabbitMQ implementation with publisher confirms, manual acknowledgements and tiered retry queues. */
public class RabbitWorkflowRecoveryQueue implements WorkflowRecoveryQueue, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RabbitWorkflowRecoveryQueue.class);
    private static final String REDIS_PREFIX = "a2a:workflow:{recovery}:rabbit:";
    private static final String DEDUP_PREFIX = REDIS_PREFIX + "dedup:";
    private static final String DEAD_GENERATION_PREFIX = REDIS_PREFIX + "dead-generation:";

    private static final DefaultRedisScript<Long> ACQUIRE_PUBLICATION = longScript("""
            if redis.call('EXISTS', KEYS[2]) == 1 then return -1 end
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then return 1 end
            return 0
            """);
    private static final DefaultRedisScript<Long> RELEASE_PUBLICATION = longScript("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """);

    private final ConnectionFactory connectionFactory;
    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final WorkflowRecoveryRabbitProperties properties;
    private final long messageTtlMs;
    private final long dedupTtlMs;
    private final List<RetryRoute> retryRoutes;
    private final ThreadLocal<Channel> workerChannel = new ThreadLocal<>();
    private final ThreadLocal<Delivery> currentDelivery = new ThreadLocal<>();
    private final Set<Channel> channels = ConcurrentHashMap.newKeySet();

    public RabbitWorkflowRecoveryQueue(ConnectionFactory connectionFactory,
                                       RabbitTemplate rabbitTemplate,
                                       AmqpAdmin amqpAdmin,
                                       StringRedisTemplate redisTemplate,
                                       ObjectMapper objectMapper,
                                       WorkflowRecoveryRabbitProperties properties,
                                       Duration messageTtl,
                                       Duration dedupTtl) {
        this.connectionFactory = connectionFactory;
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.messageTtlMs = Math.max(60_000L, messageTtl.toMillis());
        this.dedupTtlMs = Math.max(10_000L, dedupTtl.toMillis());
        this.retryRoutes = buildRetryRoutes(properties);
    }

    @Override
    public boolean publish(WorkflowRecoveryCommand command) {
        String generation = generation(command);
        String guardToken = command.recoveryId();
        Long acquired = redisTemplate.execute(ACQUIRE_PUBLICATION,
                List.of(DEDUP_PREFIX + generation, DEAD_GENERATION_PREFIX + generation),
                guardToken, String.valueOf(dedupTtlMs));
        if (!Long.valueOf(1L).equals(acquired)) return false;
        try {
            publishConfirmed(properties.getExchange(), properties.getRoutingKey(), command);
            return true;
        } catch (RuntimeException error) {
            redisTemplate.execute(RELEASE_PUBLICATION,
                    List.of(DEDUP_PREFIX + generation), guardToken);
            throw error;
        }
    }

    @Override
    public Optional<WorkflowRecoveryCommand> poll() {
        if (currentDelivery.get() != null) {
            throw new IllegalStateException("Previous RabbitMQ recovery delivery was not settled");
        }
        try {
            Channel channel = channel();
            GetResponse response = channel.basicGet(properties.getQueue(), false);
            if (response == null) return Optional.empty();
            try {
                WorkflowRecoveryCommand message = objectMapper.readValue(
                        response.getBody(), WorkflowRecoveryCommand.class);
                currentDelivery.set(new Delivery(channel, response.getEnvelope().getDeliveryTag()));
                return Optional.of(message);
            } catch (Exception malformed) {
                channel.basicReject(response.getEnvelope().getDeliveryTag(), false);
                log.warn("[WorkflowRecovery] malformed RabbitMQ message rejected to dead letter", malformed);
                return Optional.empty();
            }
        } catch (IOException error) {
            invalidateWorkerChannel();
            throw new IllegalStateException("Cannot poll RabbitMQ workflow recovery queue", error);
        }
    }

    @Override
    public void acknowledge(WorkflowRecoveryCommand command) {
        Delivery delivery = requireDelivery(command);
        try {
            delivery.channel().basicAck(delivery.deliveryTag(), false);
            currentDelivery.remove();
        } catch (IOException error) {
            invalidateWorkerChannel();
            throw new IllegalStateException("Cannot acknowledge RabbitMQ recovery message", error);
        }
    }

    @Override
    public boolean retry(WorkflowRecoveryCommand command, String error, Duration delay) {
        Delivery delivery = requireDelivery(command);
        WorkflowRecoveryCommand retry = command.nextAttempt(error);
        RetryRoute route = retryRoute(delay);
        try {
            publishConfirmed(properties.getExchange(), route.routingKey(), retry);
            delivery.channel().basicAck(delivery.deliveryTag(), false);
            currentDelivery.remove();
            return true;
        } catch (Exception publishError) {
            requeueAfterSettlementFailure(delivery);
            throw new IllegalStateException("Cannot publish RabbitMQ recovery retry", publishError);
        }
    }

    @Override
    public void deadLetter(WorkflowRecoveryCommand command, String error) {
        Delivery delivery = requireDelivery(command);
        WorkflowRecoveryCommand failed = command.withError(error);
        try {
            publishConfirmed(properties.getDeadLetterExchange(),
                    properties.getDeadLetterRoutingKey(), failed);
            redisTemplate.opsForValue().set(DEAD_GENERATION_PREFIX + generation(command),
                    command.recoveryId(), Duration.ofMillis(messageTtlMs));
            delivery.channel().basicAck(delivery.deliveryTag(), false);
            currentDelivery.remove();
        } catch (Exception publishError) {
            requeueAfterSettlementFailure(delivery);
            throw new IllegalStateException("Cannot dead-letter RabbitMQ recovery message", publishError);
        }
    }

    @Override
    public long readySize() {
        var information = amqpAdmin.getQueueInfo(properties.getQueue());
        return information != null ? information.getMessageCount() : 0L;
    }

    @Override
    public long deadLetterSize() {
        var information = amqpAdmin.getQueueInfo(properties.getDeadLetterQueue());
        return information != null ? information.getMessageCount() : 0L;
    }

    private void publishConfirmed(String exchange, String routingKey, WorkflowRecoveryCommand payload) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(payload);
            Message message = MessageBuilder.withBody(body)
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setContentEncoding(StandardCharsets.UTF_8.name())
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .setMessageId(payload.recoveryId())
                    .setHeader("workflow-request-id", payload.requestId())
                    .setHeader("workflow-recovery-attempt", payload.attempts())
                    .build();
            CorrelationData correlation = new CorrelationData(
                    payload.recoveryId() + ":" + UUID.randomUUID());
            rabbitTemplate.send(exchange, routingKey, message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(
                    properties.getConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                throw new IllegalStateException("RabbitMQ rejected recovery message: " + confirm.reason());
            }
            if (correlation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ returned unroutable recovery message: "
                        + correlation.getReturned().getReplyText());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RabbitMQ recovery publish interrupted", interrupted);
        } catch (Exception error) {
            if (error instanceof IllegalStateException illegalState) throw illegalState;
            throw new IllegalStateException("RabbitMQ recovery publish was not confirmed", error);
        }
    }

    private Channel channel() throws IOException {
        Channel existing = workerChannel.get();
        if (existing != null && existing.isOpen()) return existing;
        Channel created = connectionFactory.createConnection().createChannel(false);
        workerChannel.set(created);
        channels.add(created);
        return created;
    }

    private Delivery requireDelivery(WorkflowRecoveryCommand command) {
        Delivery delivery = currentDelivery.get();
        if (delivery == null) {
            throw new IllegalStateException("No RabbitMQ delivery is active for " + command.recoveryId());
        }
        return delivery;
    }

    private void requeueAfterSettlementFailure(Delivery delivery) {
        try {
            delivery.channel().basicNack(delivery.deliveryTag(), false, true);
        } catch (IOException nackError) {
            log.warn("[WorkflowRecovery] RabbitMQ delivery could not be requeued", nackError);
            invalidateWorkerChannel();
        } finally {
            currentDelivery.remove();
        }
    }

    private RetryRoute retryRoute(Duration requestedDelay) {
        long requestedMs = Math.max(0L, requestedDelay.toMillis());
        return retryRoutes.stream()
                .filter(route -> route.delayMs() >= requestedMs)
                .findFirst()
                .orElseGet(() -> retryRoutes.getLast());
    }

    private void invalidateWorkerChannel() {
        Channel channel = workerChannel.get();
        workerChannel.remove();
        currentDelivery.remove();
        if (channel == null) return;
        channels.remove(channel);
        try {
            closePhysically(channel);
        } catch (Exception ignored) {
            // The broker will requeue unacknowledged deliveries when the channel is gone.
        }
    }

    @Override
    public void destroy() {
        for (Channel channel : new ArrayList<>(channels)) {
            try {
                closePhysically(channel);
            } catch (Exception ignored) {
                // Connection-factory shutdown is the final fallback.
            }
        }
        channels.clear();
    }

    private static void closePhysically(Channel channel) throws Exception {
        if (channel instanceof ChannelProxy proxy) {
            proxy.getTargetChannel().close();
        } else {
            channel.close();
        }
    }

    public static List<RetryRoute> buildRetryRoutes(WorkflowRecoveryRabbitProperties properties) {
        List<Duration> configured = properties.getRetryDelays();
        if (configured == null || configured.isEmpty()) configured = List.of(Duration.ofSeconds(1));
        return configured.stream()
                .map(delay -> Math.max(1_000L, delay.toMillis()))
                .distinct()
                .sorted(Comparator.naturalOrder())
                .map(delayMs -> new RetryRoute(delayMs,
                        properties.getRoutingKey() + ".retry." + delayMs))
                .toList();
    }

    public static String retryQueueName(WorkflowRecoveryRabbitProperties properties, long delayMs) {
        return properties.getQueue() + ".retry." + delayMs;
    }

    private static String generation(WorkflowRecoveryCommand command) {
        return command.requestId() + ":" + command.checkpointUpdatedAtEpochMs()
                + ":" + command.trigger();
    }

    private static DefaultRedisScript<Long> longScript(String source) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(source);
        script.setResultType(Long.class);
        return script;
    }

    public record RetryRoute(long delayMs, String routingKey) {
    }

    private record Delivery(Channel channel, long deliveryTag) {
    }
}
