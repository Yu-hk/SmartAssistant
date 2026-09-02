package com.example.smartassistant.router.service.core;

import com.example.smartassistant.routing.contract.RoutingKeys;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserProfileContextAwaiterTest {

    @Test
    void waitsUntilConsumerPublishesReadyProfile() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(RoutingKeys.userProfileContext("req-1")))
                .thenReturn(RoutingKeys.USER_PROFILE_PENDING,
                        RoutingKeys.USER_PROFILE_PENDING,
                        RoutingKeys.USER_PROFILE_READY_PREFIX + "【用户历史信息】\n- 预算范围: 5000元");
        UserProfileContextAwaiter awaiter = awaiter(redis);

        assertThat(awaiter.await("req-1")).contains("预算范围: 5000元");
    }

    @Test
    void rejectsProductExecutionWhenProfilePreparationFailed() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(RoutingKeys.userProfileContext("req-failed")))
                .thenReturn(RoutingKeys.USER_PROFILE_FAILED);
        UserProfileContextAwaiter awaiter = awaiter(redis);

        assertThatThrownBy(() -> awaiter.await("req-failed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("preparation failed");
    }

    @Test
    void directRouterRequestWithoutConsumerMarkerRemainsCompatible() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        UserProfileContextAwaiter awaiter = awaiter(redis);

        assertThat(awaiter.await("direct-request")).isNull();
    }

    private static UserProfileContextAwaiter awaiter(StringRedisTemplate redis) {
        UserProfileContextAwaiter awaiter = new UserProfileContextAwaiter();
        ReflectionTestUtils.setField(awaiter, "redisTemplate", redis);
        ReflectionTestUtils.setField(awaiter, "waitTimeoutMs", 100L);
        ReflectionTestUtils.setField(awaiter, "pollIntervalMs", 1L);
        return awaiter;
    }
}
