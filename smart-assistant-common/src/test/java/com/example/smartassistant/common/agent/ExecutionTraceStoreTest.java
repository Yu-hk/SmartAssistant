package com.example.smartassistant.common.agent;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionTraceStoreTest {

    @Test
    void persistedTraceReceivesRealTwentyFourHourTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> list = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(list);
        when(list.size(anyString())).thenReturn(1L);
        ExecutionTraceStore store = new ExecutionTraceStore(redis);

        store.publishEvent("request-1", "router", AgentExecutionState.State.RUNNING,
                AgentExecutionState.State.COMPLETED,
                AgentExecutionState.EventType.EXECUTION_COMPLETED,
                "done", 10, 1);

        verify(redis).expire("a2a:agent-events:request-1", 24, TimeUnit.HOURS);
    }
}
