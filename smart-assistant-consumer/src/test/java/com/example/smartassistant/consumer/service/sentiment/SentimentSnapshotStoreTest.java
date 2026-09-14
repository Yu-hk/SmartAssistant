package com.example.smartassistant.consumer.service.sentiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SentimentSnapshotStoreTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final SentimentSnapshotStore store = new SentimentSnapshotStore(redis, mapper, 3600);

    @Test void keysIsolateUsersSessionsAndChangedInputWithoutStoringPlaintext() {
        String key = store.requestKey(42L, "session", "request", "我要投诉");
        assertEquals(key, store.requestKey(42L, "session", "request", "我要投诉"));
        assertNotEquals(key, store.requestKey(43L, "session", "request", "我要投诉"));
        assertNotEquals(key, store.requestKey(42L, "other", "request", "我要投诉"));
        assertNotEquals(key, store.requestKey(42L, "session", "request", "谢谢"));
        assertFalse(key.contains("我要投诉"));
        assertThrows(IllegalArgumentException.class, () -> store.requestKey(null, "session", "request", "你好"));
    }

    @Test void claimExpiresAndSnapshotsRoundTrip() throws Exception {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent("key:owner", "owner", Duration.ofSeconds(30))).thenReturn(true);
        var insight = TurnInsight.unknown("TIMEOUT", 750);
        when(values.get("key")).thenReturn(mapper.writeValueAsString(insight));
        assertTrue(store.claim("key", "owner"));
        assertEquals(insight, store.read("key"));
    }

    @Test void commitUsesAtomicScriptWithSameClusterSlotAndReturnsPersistedState() throws Exception {
        String key = store.requestKey(42L, "session", "request", "太慢了");
        TurnInsight observed = TurnInsight.analyzed(new SentimentAnalysisService.SentimentResult(
                3, "轻微负面", "共情", false, false, 95), 1);
        TurnInsight persisted = new TurnInsight("ANALYZED", 3, "轻微负面", 95, true, false,
                "EMPATHETIC", "ELEVATED", "HEURISTIC_ANALYSIS", 1, true);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenAnswer(call -> {
                    List<String> keys = call.getArgument(1);
                    assertEquals(3, keys.size());
                    String slot = key.substring(key.indexOf('{'), key.indexOf('}') + 1);
                    assertTrue(keys.stream().allMatch(value -> value.contains(slot)));
                    assertEquals("owner", call.getArgument(2));
                    assertEquals(observed, mapper.readValue((String) call.getArgument(3), TurnInsight.class));
                    assertEquals("3600", call.getArgument(4));
                    return mapper.writeValueAsString(persisted);
                });
        assertEquals(persisted, store.commit(key, "owner", 42L, "session", observed));
    }

    @Test void missingOwnershipDoesNotClaimPersistence() {
        var insight = TurnInsight.unknown("TIMEOUT", 750);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("");
        assertFalse(store.commit("key", "stale", 42L, "session", insight).stateRecorded());
    }
}
