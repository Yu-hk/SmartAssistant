package com.example.smartassistant.common.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTaskQueueReliabilityTest {

    @Mock StringRedisTemplate redis;
    @Mock ListOperations<String, String> lists;
    @Mock ValueOperations<String, String> values;

    private AgentTaskQueue queue;

    @BeforeEach
    void setUp() {
        when(redis.opsForList()).thenReturn(lists);
        when(redis.opsForValue()).thenReturn(values);
        queue = new AgentTaskQueue(redis);
    }

    @Test
    void claimsAtomicallyAndKeepsDeliveryUntilExplicitAck() throws Exception {
        AgentTask task = new AgentTask();
        task.setTaskId("task-1");
        task.setAgentName("product");
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        when(lists.rightPopAndLeftPush(
                "a2a:queue:tasks", "a2a:queue:processing", 2, TimeUnit.SECONDS))
                .thenReturn("task-1");
        when(values.get("a2a:queue:task:task-1")).thenReturn(mapper.writeValueAsString(task));

        Optional<AgentTask> claimed = queue.dequeue(2);

        assertThat(claimed).isPresent();
        assertThat(claimed.orElseThrow().getStatus()).isEqualTo(AgentTaskStatus.RUNNING);
        queue.acknowledge("task-1");
        verify(lists).remove("a2a:queue:processing", 1, "task-1");
    }

    @Test
    void retryAcknowledgesOldDeliveryAndCreatesNewPendingDelivery() {
        AgentTask task = new AgentTask();
        task.setTaskId("task-2");
        task.markFailed("temporary failure");
        task.setRetryCount(1);

        queue.retry(task);

        assertThat(task.getStatus()).isEqualTo(AgentTaskStatus.PENDING);
        verify(lists).remove("a2a:queue:processing", 1, "task-2");
        verify(lists).leftPush("a2a:queue:tasks", "task-2");
    }

    @Test
    void reclaimsAnAbandonedDeliveryWithinTheSameRetryBudget() throws Exception {
        AgentTask task = new AgentTask();
        task.setTaskId("task-3");
        task.markRunning();
        task.setStartedAt(LocalDateTime.now().minusMinutes(5));
        task.setMaxRetries(2);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        when(lists.range("a2a:queue:processing", 0, -1)).thenReturn(List.of("task-3"));
        when(values.get("a2a:queue:task:task-3")).thenReturn(mapper.writeValueAsString(task));

        int reclaimed = queue.reclaimTimedOut(Duration.ofMinutes(2));

        assertThat(reclaimed).isEqualTo(1);
        verify(lists).remove("a2a:queue:processing", 1, "task-3");
        verify(lists).leftPush("a2a:queue:tasks", "task-3");
    }

}
