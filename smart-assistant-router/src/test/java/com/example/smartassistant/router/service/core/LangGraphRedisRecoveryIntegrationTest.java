package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.service.checkpoint.LangGraphRedisCheckpointSaver;
import com.example.smartassistant.router.service.recovery.WorkflowExecutionLeaseService;
import com.example.smartassistant.router.service.recovery.InMemoryWorkflowRecoveryJobRepository;
import com.example.smartassistant.router.service.recovery.WorkflowRecoveryApplicationService;
import com.example.smartassistant.router.service.recovery.WorkflowRecoveryManager;
import com.example.smartassistant.router.service.recovery.WorkflowRecoveryQueue;
import com.example.smartassistant.router.service.recovery.RedisWorkflowRecoveryQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "ROUTER_RECOVERY_REDIS_PORT", matches = "\\d+")
class LangGraphRedisRecoveryIntegrationTest {

    private static final String REQUEST_ID = "redis-recovery-integration";
    private static final String CHECKPOINT_KEY = LangGraphRedisCheckpointSaver.KEY_PREFIX + REQUEST_ID;

    private final List<LettuceConnectionFactory> connectionFactories = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (ExecutorService executor : executors) executor.shutdownNow();
        for (LettuceConnectionFactory connectionFactory : connectionFactories) {
            connectionFactory.destroy();
        }
    }

    @Test
    void resumesFromRedisAfterRouterInstanceIsReplaced() throws Exception {
        int port = Integer.parseInt(System.getenv("ROUTER_RECOVERY_REDIS_PORT"));
        StringRedisTemplate redisA = redisTemplate(port);
        redisA.delete(CHECKPOINT_KEY);

        GraphNodeExecutionService nodeExecutor = mock(GraphNodeExecutionService.class);
        TaskPlannerService planner = mock(TaskPlannerService.class);
        IntentGraph.IntentNode inventory = new IntentGraph.IntentNode(
                "inventory", "查询库存", "product", List.of());
        IntentGraph.IntentNode order = new IntentGraph.IntentNode(
                "order", "创建订单", "order", List.of("inventory"), null, List.of(), true);
        IntentGraph graph = new IntentGraph("查询库存后创建订单", List.of(inventory, order));

        when(nodeExecutor.execute(eq(inventory), anyMap(), any(), eq(7L), eq("events"),
                eq(REQUEST_ID), any(), any(), any(), eq("查询库存后创建订单")))
                .thenReturn(result("inventory", "product", "库存充足"));
        when(nodeExecutor.execute(org.mockito.ArgumentMatchers.argThat(
                        node -> "order".equals(node.getId())), anyMap(), any(), eq(7L),
                eq("events"), eq(REQUEST_ID), any(), any(), any(),
                eq("查询库存后创建订单")))
                .thenReturn(result("order", "order", "订单已创建"));

        LangGraphRouteExecutionService instanceA = service(
                nodeExecutor, planner, new LangGraphRedisCheckpointSaver(redisA));
        List<SubTaskResult> paused = instanceA.execute(graph, 7L, "events", REQUEST_ID);

        assertThat(paused).extracting(SubTaskResult::getTaskId)
                .containsExactly("inventory", "order:approval");
        assertThat(paused.getLast().getSystemNodeType())
                .isEqualTo(SubTaskResult.SystemNodeType.APPROVAL);
        assertThat(redisA.hasKey(CHECKPOINT_KEY)).isTrue();
        Long ttl = redisA.getExpire(CHECKPOINT_KEY);
        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(3_600L);
        verify(nodeExecutor, times(1)).execute(eq(inventory), anyMap(), any(), eq(7L),
                eq("events"), eq(REQUEST_ID), any(), any(), any(), any(String.class));

        // Stop every process-local resource owned by Router A before creating Router B.
        executors.removeFirst().shutdownNow();
        connectionFactories.removeFirst().destroy();

        // A new connection, saver, executor and service represent a replacement Router process.
        StringRedisTemplate redisB = redisTemplate(port);
        LangGraphRouteExecutionService instanceB = service(
                nodeExecutor, planner, new LangGraphRedisCheckpointSaver(redisB));

        assertThatThrownBy(() -> instanceB.resumeApproved(8L, REQUEST_ID))
                .isInstanceOf(SecurityException.class);

        List<SubTaskResult> resumed = instanceB.resumeApproved(7L, REQUEST_ID);
        assertThat(resumed).extracting(SubTaskResult::getTaskId)
                .containsExactly("inventory", "order");
        assertThat(resumed.getFirst().getResult()).isEqualTo("库存充足");
        assertThat(resumed.getLast().getResult()).isEqualTo("订单已创建");
        verify(nodeExecutor, times(1)).execute(eq(inventory), anyMap(), any(), eq(7L),
                eq("events"), eq(REQUEST_ID), any(), any(), any(), any(String.class));
        verify(nodeExecutor, times(1)).execute(org.mockito.ArgumentMatchers.argThat(
                        node -> "order".equals(node.getId())), anyMap(), any(), eq(7L),
                eq("events"), eq(REQUEST_ID), any(), any(), any(), any(String.class));

        assertThat(redisB.hasKey(CHECKPOINT_KEY)).isFalse();
        assertThat(new LangGraphRedisCheckpointSaver(redisB)
                .get(RunnableConfig.builder().threadId(REQUEST_ID).build())).isEmpty();
        assertThatThrownBy(() -> instanceB.resumeApproved(7L, REQUEST_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist or has expired");
    }

    @Test
    void replaysOnlyTheInFlightNodeAfterRouterCrash() {
        int port = Integer.parseInt(System.getenv("ROUTER_RECOVERY_REDIS_PORT"));
        String requestId = "redis-node-crash-recovery";
        String checkpointKey = LangGraphRedisCheckpointSaver.KEY_PREFIX + requestId;
        StringRedisTemplate redisA = redisTemplate(port);
        redisA.delete(checkpointKey);

        GraphNodeExecutionService nodeExecutor = mock(GraphNodeExecutionService.class);
        TaskPlannerService planner = mock(TaskPlannerService.class);
        IntentGraph.IntentNode inventory = new IntentGraph.IntentNode(
                "inventory", "查询库存", "product", List.of());
        IntentGraph.IntentNode order = new IntentGraph.IntentNode(
                "order", "创建订单", "order", List.of("inventory"));
        IntentGraph graph = new IntentGraph("崩溃恢复", List.of(inventory, order));

        when(nodeExecutor.execute(eq(inventory), anyMap(), any(), eq(7L), eq("events"),
                eq(requestId), any(), any(), any(), eq("崩溃恢复")))
                .thenReturn(result("inventory", "product", "库存充足"));
        when(nodeExecutor.execute(org.mockito.ArgumentMatchers.argThat(
                        node -> "order".equals(node.getId())), anyMap(), any(), eq(7L), eq("events"),
                eq(requestId), any(), any(), any(), eq("崩溃恢复")))
                .thenThrow(new IllegalStateException("simulated process crash"))
                .thenReturn(result("order", "order", "订单已创建"));

        LangGraphRouteExecutionService instanceA = service(
                nodeExecutor, planner, new LangGraphRedisCheckpointSaver(redisA));
        assertThatThrownBy(() -> instanceA.execute(graph, 7L, "events", requestId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execution failed");
        assertThat(redisA.hasKey(checkpointKey)).isTrue();

        executors.removeFirst().shutdownNow();
        connectionFactories.removeFirst().destroy();

        StringRedisTemplate redisB = redisTemplate(port);
        LangGraphRouteExecutionService instanceB = service(
                nodeExecutor, planner, new LangGraphRedisCheckpointSaver(redisB));
        assertThatThrownBy(() -> instanceB.resumeInterrupted(8L, requestId))
                .isInstanceOf(SecurityException.class);

        List<SubTaskResult> resumed = instanceB.resumeInterrupted(7L, requestId);
        assertThat(resumed).extracting(SubTaskResult::getTaskId)
                .containsExactly("inventory", "order");
        verify(nodeExecutor, times(1)).execute(eq(inventory), anyMap(), any(), eq(7L),
                eq("events"), eq(requestId), any(), any(), any(), any(String.class));
        verify(nodeExecutor, times(2)).execute(org.mockito.ArgumentMatchers.argThat(
                        node -> "order".equals(node.getId())), anyMap(), any(), eq(7L),
                eq("events"), eq(requestId), any(), any(), any(), any(String.class));
        assertThat(redisB.hasKey(checkpointKey)).isFalse();
    }

    @Test
    void scannerPublishesAndConsumerAutomaticallyResumesCrashedWorkflow() throws Exception {
        int port = Integer.parseInt(System.getenv("ROUTER_RECOVERY_REDIS_PORT"));
        String requestId = "redis-auto-node-crash-recovery";
        StringRedisTemplate redisA = redisTemplate(port);
        redisA.delete(LangGraphRedisCheckpointSaver.KEY_PREFIX + requestId);
        redisA.opsForZSet().remove(LangGraphRedisCheckpointSaver.INDEX_KEY, requestId);
        redisA.delete(redisA.keys("a2a:workflow:{recovery}:*"));

        GraphNodeExecutionService nodeExecutor = mock(GraphNodeExecutionService.class);
        TaskPlannerService planner = mock(TaskPlannerService.class);
        IntentGraph.IntentNode inventory = new IntentGraph.IntentNode(
                "inventory-auto", "查询库存", "product", List.of());
        IntentGraph.IntentNode order = new IntentGraph.IntentNode(
                "order-auto", "创建订单", "order", List.of("inventory-auto"));
        IntentGraph graph = new IntentGraph("自动崩溃恢复", List.of(inventory, order));

        when(nodeExecutor.execute(eq(inventory), anyMap(), any(), eq(9L), eq("events-auto"),
                eq(requestId), any(), any(), any(), eq("自动崩溃恢复")))
                .thenReturn(result("inventory-auto", "product", "库存充足"));
        when(nodeExecutor.execute(org.mockito.ArgumentMatchers.argThat(
                        node -> "order-auto".equals(node.getId())), anyMap(), any(), eq(9L),
                eq("events-auto"), eq(requestId), any(), any(), any(), eq("自动崩溃恢复")))
                .thenThrow(new IllegalStateException("simulated process crash"))
                .thenReturn(result("order-auto", "order", "订单已创建"));

        LangGraphRouteExecutionService instanceA = service(
                nodeExecutor, planner, new LangGraphRedisCheckpointSaver(redisA));
        assertThatThrownBy(() -> instanceA.execute(graph, 9L, "events-auto", requestId))
                .isInstanceOf(IllegalStateException.class);

        StringRedisTemplate redisB = redisTemplate(port);
        LangGraphRedisCheckpointSaver saverB = new LangGraphRedisCheckpointSaver(redisB);
        LangGraphRouteExecutionService instanceB = service(nodeExecutor, planner, saverB);
        WorkflowExecutionLeaseService leaseService = new WorkflowExecutionLeaseService(redisB, 3_000L);
        instanceB.setExecutionLeaseService(leaseService);
        WorkflowRecoveryQueue queue = new RedisWorkflowRecoveryQueue(redisB, new ObjectMapper(),
                Duration.ofHours(1), Duration.ofMinutes(1));
        WorkflowRecoveryApplicationService recoveryService =
                new WorkflowRecoveryApplicationService(saverB, queue, leaseService, instanceB,
                        new InMemoryWorkflowRecoveryJobRepository());
        WorkflowRecoveryManager manager = new WorkflowRecoveryManager(
                saverB, queue, recoveryService, 1_000L,
                Duration.ofSeconds(2), 10, 2, 1, 20L);
        try {
            redisB.opsForZSet().add(LangGraphRedisCheckpointSaver.INDEX_KEY,
                    requestId, System.currentTimeMillis() - 10_000L);
            manager.afterPropertiesSet();
            assertThat(manager.scanAndPublish()).isEqualTo(1);

            long deadline = System.currentTimeMillis() + 8_000L;
            while (saverB.get(RunnableConfig.builder().threadId(requestId).build()).isPresent()
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(25L);
            }
            assertThat(saverB.get(RunnableConfig.builder().threadId(requestId).build())).isEmpty();
            verify(nodeExecutor, times(1)).execute(eq(inventory), anyMap(), any(), eq(9L),
                    eq("events-auto"), eq(requestId), any(), any(), any(), any(String.class));
            verify(nodeExecutor, times(2)).execute(org.mockito.ArgumentMatchers.argThat(
                            node -> "order-auto".equals(node.getId())), anyMap(), any(), eq(9L),
                    eq("events-auto"), eq(requestId), any(), any(), any(), any(String.class));
        } finally {
            manager.destroy();
            leaseService.destroy();
        }
    }

    private LangGraphRouteExecutionService service(GraphNodeExecutionService nodeExecutor,
                                                    TaskPlannerService planner,
                                                    LangGraphRedisCheckpointSaver saver) {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        executors.add(executor);
        LangGraphRouteExecutionService service = new LangGraphRouteExecutionService(
                nodeExecutor, planner, saver, executor);
        ReflectionTestUtils.setField(service, "maxReplans", 1);
        return service;
    }

    private StringRedisTemplate redisTemplate(int port) {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory("127.0.0.1", port);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        connectionFactories.add(connectionFactory);
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }

    private static SubTaskResult result(String id, String agent, String text) {
        return new SubTaskResult(id, text, agent, text, true);
    }
}
