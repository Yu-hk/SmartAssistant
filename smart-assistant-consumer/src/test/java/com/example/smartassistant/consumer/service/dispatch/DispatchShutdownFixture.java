package com.example.smartassistant.consumer.service.dispatch;

import com.example.smartassistant.consumer.client.RouterClient;
import com.example.smartassistant.consumer.config.ChatDispatchProperties;
import com.example.smartassistant.consumer.config.ChatDispatchRabbitConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;

/**
 * Opt-in child JVM probe: real dispatch store/listener/topology and real Redis/MQ.
 * No Boot component scan, HTTP, Nacos, model, user data, order or wallet access.
 * Only the Router and the deliberate ACK barrier are synthetic.
 */
public final class DispatchShutdownFixture {
    private static final String PREFIX = "shutdown-probe:";

    public static void main(String[] args) throws Exception {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-probe", Map.of(
                "chat.dispatch.enabled", "false", "chat.dispatch.concurrency", "1",
                "chat.dispatch.queue-wait-ms", "60000")));
        context.register(Setup.class);
        context.refresh();
        context.getBean(RabbitAdmin.class).initialize();
        var redis = context.getBean(StringRedisTemplate.class);
        if (args.length == 2 && "seed".equals(args[0])) {
            try {
                var command = new ChatDispatchCommand(900000001L, "synthetic-" + args[1], args[1],
                        "synthetic read-only calculation", false, 0, System.currentTimeMillis() + 60000);
                context.getBean(ChatDispatchStore.class).reserve(command);
                context.getBean(RabbitTemplate.class).send(ChatDispatchRabbitConfig.EXCHANGE,
                        ChatDispatchRabbitConfig.ROUTING_KEY,
                        new Message(context.getBean(ObjectMapper.class).writeValueAsBytes(command),
                                new org.springframework.amqp.core.MessageProperties()));
                System.out.println("PROBE_SEEDED " + args[1]);
            } finally {
                context.close();
            }
            return;
        }
        if (args.length != 2 || !"worker".equals(args[0])) {
            context.close();
            throw new IllegalArgumentException("worker <generation> or seed <request-id> required");
        }
        String generation = args[1];
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("PROBE_TERM_BEGIN " + generation);
            context.close();
            System.out.println("PROBE_TERM_END " + generation);
        }, "probe-shutdown"));
        redis.opsForValue().set(PREFIX + "ready:" + generation, "1", Duration.ofMinutes(10));
        System.out.println("PROBE_READY " + generation);
        while (!"1".equals(redis.opsForValue().get(PREFIX + "start:" + generation))) Thread.sleep(50);
        context.getBean(RabbitListenerEndpointRegistry.class).start();
        System.out.println("PROBE_CONSUMING " + generation);
        while (true) Thread.sleep(1000);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRabbit
    @Import(ChatDispatchRabbitConfig.class)
    static class Setup {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean(destroyMethod = "destroy") LettuceConnectionFactory redisConnection() {
            return new LettuceConnectionFactory("redis", 6379);
        }
        @Bean StringRedisTemplate redis(LettuceConnectionFactory connection) {
            return new StringRedisTemplate(connection);
        }
        @Bean(destroyMethod = "destroy") CachingConnectionFactory rabbitConnection() {
            var connection = new CachingConnectionFactory("rabbitmq", 5672);
            connection.setUsername("probe");
            connection.setPassword("isolated-test-only");
            return connection;
        }
        @Bean RabbitAdmin admin(CachingConnectionFactory connection) { return new RabbitAdmin(connection); }
        @Bean RabbitTemplate publisher(CachingConnectionFactory connection) { return new RabbitTemplate(connection); }
        @Bean ChatDispatchStore store(StringRedisTemplate redis, ObjectMapper mapper) {
            return new ChatDispatchStore(redis, mapper);
        }
        @Bean ProbeListener probeListener(ChatDispatchStore store, StringRedisTemplate redis, ObjectMapper mapper) {
            var fakeRouter = new RouterClient(redis, mapper, 100, 100) {
                @Override public Map<String, Object> callRouterRaw(String question, String userId, String sessionId,
                                                                 String requestId, boolean allowAnswerCache) {
                    redis.opsForValue().increment(PREFIX + "calls:" + requestId);
                    awaitRelease(redis, requestId, "route");
                    return Map.of("result", "synthetic verified result", "workflowStatus", "COMPLETED");
                }
            };
            return new ProbeListener(new ChatDispatchListener(store, fakeRouter, mapper, redis, null), redis, mapper);
        }
    }

    static class ProbeListener {
        private final ChatDispatchListener actual;
        private final StringRedisTemplate redis;
        private final ObjectMapper mapper;
        ProbeListener(ChatDispatchListener actual, StringRedisTemplate redis, ObjectMapper mapper) {
            this.actual = actual; this.redis = redis; this.mapper = mapper;
        }
        @RabbitListener(queues = ChatDispatchRabbitConfig.QUEUE, containerFactory = "chatDispatchContainerFactory",
                autoStartup = "${chat.dispatch.enabled:false}")
        public void receive(Message message, Channel channel) throws Exception {
            String request = mapper.readValue(message.getBody(), ChatDispatchCommand.class).requestId();
            Channel withBarrier = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(),
                    new Class<?>[]{Channel.class}, (proxy, method, args) -> {
                        if ("basicAck".equals(method.getName()) &&
                                "ack".equals(redis.opsForValue().get(PREFIX + "hold:" + request))) {
                            redis.opsForValue().set(PREFIX + "ack-barrier:" + request, "1", Duration.ofMinutes(10));
                            awaitRelease(redis, request, "ack");
                        }
                        try {
                            return method.invoke(channel, args);
                        } catch (InvocationTargetException error) {
                            throw error.getCause();
                        }
                    });
            actual.receive(message, withBarrier);
        }
    }

    private static void awaitRelease(StringRedisTemplate redis, String request, String stage) {
        if (!stage.equals(redis.opsForValue().get(PREFIX + "hold:" + request))) return;
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (!"1".equals(redis.opsForValue().get(PREFIX + "release:" + request))) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Synthetic barrier expired");
            try { Thread.sleep(50); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Synthetic barrier interrupted", error);
            }
        }
    }
}
