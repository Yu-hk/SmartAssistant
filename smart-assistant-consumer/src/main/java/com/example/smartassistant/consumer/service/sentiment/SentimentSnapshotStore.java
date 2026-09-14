package com.example.smartassistant.consumer.service.sentiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/** Redis-backed request deduplication and bounded, user/session-isolated emotion history. */
@Service
public class SentimentSnapshotStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final long ttlSeconds;

    private static final DefaultRedisScript<String> COMMIT = new DefaultRedisScript<>("""
            local existing = redis.call('GET', KEYS[1])
            if existing then return existing end
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then return '' end
            local value = cjson.decode(ARGV[2])
            -- UNKNOWN breaks the observed streak instead of pretending to be neutral.
            redis.call('RPUSH', KEYS[3], value.level ~= cjson.null and value.level or 0)
            redis.call('LTRIM', KEYS[3], -5, -1)
            redis.call('EXPIRE', KEYS[3], ARGV[3])
            local recent = redis.call('LRANGE', KEYS[3], -3, -1)
            local escalated = #recent == 3
            for _, level in ipairs(recent) do
                if tonumber(level) < 3 then escalated = false end
            end
            value.escalated = escalated
            if escalated then value.suggestedPriority = 'ELEVATED' end
            value.stateRecorded = true
            local encoded = cjson.encode(value)
            redis.call('SET', KEYS[1], encoded, 'EX', ARGV[3])
            redis.call('DEL', KEYS[2])
            return encoded
            """, String.class);

    public SentimentSnapshotStore(StringRedisTemplate redis, ObjectMapper mapper,
            @Value("${consumer.sentiment.state-ttl-seconds:3600}") long ttlSeconds) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttlSeconds = Math.max(60, ttlSeconds);
    }

    public String requestKey(Long userId, String sessionId, String requestId, String question) {
        if (userId == null || userId <= 0 || sessionId == null || sessionId.isBlank()
                || requestId == null || requestId.isBlank()) throw new IllegalArgumentException("Invalid turn identity");
        // Include input fingerprint so a reused ID cannot return observations about different input.
        return sessionKey(userId, sessionId) + ":request:" + digest(requestId + "\n" + question);
    }

    private String sessionKey(Long userId, String sessionId) {
        return "consumer:sentiment:v1:{" + userId + ":" + digest(sessionId) + "}";
    }

    public TurnInsight read(String key) {
        String json = redis.opsForValue().get(key);
        return json == null ? null : decode(json);
    }

    public boolean claim(String key, String owner) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key + ":owner", owner, Duration.ofSeconds(30)));
    }

    public TurnInsight commit(String key, String owner, Long userId, String sessionId, TurnInsight insight) {
        try {
            String json = redis.execute(COMMIT, List.of(key, key + ":owner", sessionKey(userId, sessionId) + ":history"),
                    owner, mapper.writeValueAsString(insight), Long.toString(ttlSeconds));
            return json == null || json.isBlank() ? insight : decode(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Invalid sentiment snapshot", error);
        }
    }

    private TurnInsight decode(String json) {
        try { return mapper.readValue(json, TurnInsight.class); }
        catch (Exception error) { throw new IllegalStateException("Invalid sentiment snapshot", error); }
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
