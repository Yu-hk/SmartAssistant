package com.example.smartassistant.router.service.core;

import com.example.smartassistant.routing.contract.RoutingKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Waits for the Consumer-owned request profile only at the Product execution boundary. */
@Service
public class UserProfileContextAwaiter {

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Value("${router.user-profile.wait-timeout-ms:10000}")
    private long waitTimeoutMs;

    @Value("${router.user-profile.poll-interval-ms:25}")
    private long pollIntervalMs;

    /**
     * @return the prepared profile, an empty string for a completed empty profile,
     *         or {@code null} when the request did not originate from profile-aware Consumer code.
     */
    public String await(String requestId) {
        if (redisTemplate == null || requestId == null || requestId.isBlank()) return null;
        String key = RoutingKeys.userProfileContext(requestId);
        String state = read(key);
        if (state == null) return null;

        long deadline = System.currentTimeMillis() + Math.max(1L, waitTimeoutMs);
        while (RoutingKeys.USER_PROFILE_PENDING.equals(state)) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                throw new IllegalStateException("User profile was not ready before Product execution");
            }
            try {
                Thread.sleep(Math.min(Math.max(1L, pollIntervalMs), remaining));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for user profile", error);
            }
            state = read(key);
            if (state == null) {
                throw new IllegalStateException("User profile coordination state expired before completion");
            }
        }

        if (RoutingKeys.USER_PROFILE_EMPTY.equals(state)) return "";
        if (state.startsWith(RoutingKeys.USER_PROFILE_READY_PREFIX)) {
            return state.substring(RoutingKeys.USER_PROFILE_READY_PREFIX.length());
        }
        if (RoutingKeys.USER_PROFILE_FAILED.equals(state)) {
            throw new IllegalStateException("User profile preparation failed before Product execution");
        }
        throw new IllegalStateException("Unknown user profile coordination state");
    }

    private String read(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read user-profile coordination state", error);
        }
    }
}
