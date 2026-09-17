package com.example.smartassistant.router.service.recovery;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InterruptedLeaseCleanupTest {
    @Test void cleanupTemporarilyClearsInterruptAndKeepsOwnerCheckedDelete() {
        var redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.opsForValue().setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            assertFalse(Thread.currentThread().isInterrupted());
            assertTrue(((RedisScript<?>) invocation.getArgument(0)).getScriptAsString().contains("~= ARGV[1]"));
            return 1L;
        });
        var leases = new WorkflowExecutionLeaseService(redis, 90000);
        try {
            var lease = leases.acquire("cancel-fixture");
            assertNotNull(lease);
            Thread.currentThread().interrupt();
            lease.close();
            assertTrue(Thread.currentThread().isInterrupted());
            verify(redis).execute(any(RedisScript.class), eq(java.util.List.of("a2a:workflow:{recovery}:lease:cancel-fixture")), any(Object[].class));
        } finally { Thread.interrupted(); leases.destroy(); }
    }
}
