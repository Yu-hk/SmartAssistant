package com.example.smartassistant.router.service.core;

import com.example.smartassistant.routing.contract.RoutingKeys;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class UserProfileContextAwaiterTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final UserProfileContextAwaiter awaiter = new UserProfileContextAwaiter();
    @BeforeEach void setup() {
        when(redis.opsForValue()).thenReturn(values);
        ReflectionTestUtils.setField(awaiter, "redisTemplate", redis);
        ReflectionTestUtils.setField(awaiter, "waitTimeoutMs", 100L);
        ReflectionTestUtils.setField(awaiter, "pollIntervalMs", 1L);
    }
    @AfterEach void close() { awaiter.close(); Thread.interrupted(); }

    @Test void waitsBrieflyForReadyAndFreezesProfileForLaterNodes() {
        when(values.get(anyString())).thenReturn(RoutingKeys.USER_PROFILE_PENDING,
                RoutingKeys.USER_PROFILE_READY_PREFIX + "已保存画像");
        assertThat(awaiter.await("request")).isEqualTo("已保存画像");
        when(values.get(anyString())).thenReturn(RoutingKeys.USER_PROFILE_READY_PREFIX + "稍后完成的新画像");
        assertThat(awaiter.await("request")).isEqualTo("已保存画像");
    }

    @Test void missingFailedEmptyAndUnknownStatesDoNotThrow() {
        for (String state : new String[]{null, RoutingKeys.USER_PROFILE_FAILED, RoutingKeys.USER_PROFILE_EMPTY, "invalid"}) {
            when(values.get(anyString())).thenReturn(state);
            assertThat(awaiter.await("request-" + state)).isEmpty();
        }
    }

    @Test void storageFailureFallsBack() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("Redis unavailable"));
        assertThat(awaiter.await("request")).isEmpty();
    }

    @Test void selectedContextIsNotReusedAcrossUsers() {
        when(values.get(anyString())).thenReturn(RoutingKeys.USER_PROFILE_READY_PREFIX + "user A",
                RoutingKeys.USER_PROFILE_READY_PREFIX + "user B");
        assertThat(awaiter.await("request", 1L)).isEqualTo("user A");
        assertThat(awaiter.await("request", 2L)).isEqualTo("user B");
    }

    @Test void timeoutIsSharedAndLateResultDoesNotChangeLaterNode() {
        when(values.get(anyString())).thenReturn(RoutingKeys.USER_PROFILE_PENDING);
        assertThat(awaiter.await("request")).isEmpty();
        when(values.get(anyString())).thenReturn(RoutingKeys.USER_PROFILE_READY_PREFIX + "too late");
        clearInvocations(values);
        assertThat(awaiter.await("request")).isEmpty();
        verifyNoInteractions(values);
    }

    @Test void slowRedisReadCannotExceedBudgetEvenWhenItIgnoresInterrupt() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(values.get(anyString())).thenAnswer(ignored -> {
            while (true) {
                try { release.await(); break; }
                catch (InterruptedException ignore) { /* simulate an uninterruptible network read */ }
            }
            return RoutingKeys.USER_PROFILE_READY_PREFIX + "too late";
        });
        try {
            long started = System.nanoTime();
            assertThat(awaiter.await("slow")).isEmpty();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(450);
        } finally { release.countDown(); }
    }

    @Test void concurrentProductNodesShareOneRead() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ReflectionTestUtils.setField(awaiter, "waitTimeoutMs", 1000L);
        when(values.get(anyString())).thenAnswer(ignored -> {
            entered.countDown(); release.await(); return RoutingKeys.USER_PROFILE_READY_PREFIX + "same";
        });
        try (var callers = Executors.newFixedThreadPool(2)) {
            Future<String> first = callers.submit(() -> awaiter.await("same-request"));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            Future<String> second = callers.submit(() -> awaiter.await("same-request"));
            release.countDown();
            assertThat(first.get()).isEqualTo("same");
            assertThat(second.get()).isEqualTo("same");
            verify(values, times(1)).get(anyString());
        } finally { release.countDown(); }
    }

    @Test void interruptedCallerIsNotConvertedToEmptyProfile() {
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> awaiter.await("cancelled")).isInstanceOf(CancellationException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verifyNoInteractions(redis);
    }

    @Test void readerRejectionDegradesImmediately() {
        awaiter.close();
        assertThat(awaiter.await("overloaded")).isEmpty();
        verifyNoInteractions(redis);
    }
}
