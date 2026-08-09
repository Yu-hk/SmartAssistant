package com.example.smartassistant.user.service;

import com.example.smartassistant.user.mapper.UserSessionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionServiceTest {

    @Test
    void revokeAccessTokenWritesGatewayBlacklistKeyBeforeRevokingSession() {
        UserSessionMapper sessionMapper = mock(UserSessionMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Duration ttl = Duration.ofMinutes(15);

        new SessionService(sessionMapper, redisTemplate).revokeAccessToken("token-jti", ttl);

        verify(valueOperations).set("blacklist:token-jti", "revoked", ttl);
        verify(redisTemplate).delete("session:token-jti");
    }

    @Test
    void consumeRefreshTokenUsesAtomicSetIfAbsent() {
        UserSessionMapper sessionMapper = mock(UserSessionMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Duration ttl = Duration.ofHours(24);
        when(valueOperations.setIfAbsent("refresh-blacklist:refresh-jti", "revoked", ttl))
                .thenReturn(true, false);
        SessionService service = new SessionService(sessionMapper, redisTemplate);

        assertTrue(service.consumeRefreshToken("refresh-jti", ttl));
        assertFalse(service.consumeRefreshToken("refresh-jti", ttl));
    }

    @Test
    void logoutCanIdempotentlyBlacklistRefreshToken() {
        UserSessionMapper sessionMapper = mock(UserSessionMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Duration ttl = Duration.ofHours(24);

        new SessionService(sessionMapper, redisTemplate).blacklistRefreshToken("refresh-jti", ttl);

        verify(valueOperations).set("refresh-blacklist:refresh-jti", "revoked", ttl);
    }
}
