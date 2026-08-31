package com.example.smartassistant.router.service.recovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Redis implementation retained as a rollback option for workflow recovery transport. */
public class RedisWorkflowRecoveryQueue implements WorkflowRecoveryQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisWorkflowRecoveryQueue.class);
    private static final String PREFIX = "a2a:workflow:{recovery}:";
    static final String READY_KEY = PREFIX + "ready";
    static final String PROCESSING_KEY = PREFIX + "processing";
    static final String MESSAGE_KEY = PREFIX + "messages";
    static final String DEDUP_KEY_PREFIX = PREFIX + "dedup:";
    static final String DEAD_LETTER_KEY = PREFIX + "dead-letter";
    static final String DEAD_GENERATION_KEY_PREFIX = PREFIX + "dead-generation:";

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> CLAIM_SCRIPT = listScript("""
            local id = redis.call('RPOP', KEYS[1])
            if not id then return {} end
            local payload = redis.call('HGET', KEYS[3], id)
            if not payload then return {'MISSING', id} end
            redis.call('ZADD', KEYS[2], ARGV[1], id)
            return {id, payload}
            """);
    private static final DefaultRedisScript<Long> PUBLISH_SCRIPT = longScript("""
            if redis.call('EXISTS', KEYS[6]) == 1 then return -1 end
            if not redis.call('SET', KEYS[4], ARGV[2], 'NX', 'PX', ARGV[6]) then return 0 end
            redis.call('HSET', KEYS[3], ARGV[3], ARGV[4])
            redis.call('LPUSH', KEYS[1], ARGV[3])
            redis.call('PEXPIRE', KEYS[3], ARGV[5])
            return 1
            """);
    private static final DefaultRedisScript<Long> ACK_SCRIPT = longScript("""
            redis.call('ZREM', KEYS[2], ARGV[1])
            redis.call('HDEL', KEYS[3], ARGV[1])
            return 1
            """);
    private static final DefaultRedisScript<Long> RETRY_SCRIPT = longScript("""
            if redis.call('ZREM', KEYS[2], ARGV[1]) == 0 then return 0 end
            redis.call('HSET', KEYS[3], ARGV[1], ARGV[2])
            redis.call('LPUSH', KEYS[1], ARGV[1])
            return 1
            """);
    private static final DefaultRedisScript<Long> DEAD_LETTER_SCRIPT = longScript("""
            if redis.call('ZREM', KEYS[2], ARGV[1]) == 0 then return 0 end
            local payload = redis.call('HGET', KEYS[3], ARGV[1])
            if payload then redis.call('LPUSH', KEYS[5], payload) end
            redis.call('HDEL', KEYS[3], ARGV[1])
            redis.call('SET', KEYS[6], ARGV[3], 'PX', ARGV[4])
            return 1
            """);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long messageTtlMs;
    private final long dedupTtlMs;

    public RedisWorkflowRecoveryQueue(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                      Duration messageTtl, Duration dedupTtl) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.messageTtlMs = Math.max(60_000L, messageTtl.toMillis());
        this.dedupTtlMs = Math.max(10_000L, dedupTtl.toMillis());
    }

    /** Publishes one message per checkpoint generation even when every Router instance scans it. */
    public boolean publish(WorkflowRecoveryCommand message) {
        try {
            String dedupId = generation(message);
            Long result = redisTemplate.execute(PUBLISH_SCRIPT, keys(dedupId), dedupId,
                    String.valueOf(System.currentTimeMillis()), message.recoveryId(),
                    objectMapper.writeValueAsString(message), String.valueOf(messageTtlMs),
                    String.valueOf(dedupTtlMs));
            return Long.valueOf(1L).equals(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize workflow recovery message", e);
        }
    }

    public Optional<WorkflowRecoveryCommand> poll() {
        List<?> result = redisTemplate.execute(CLAIM_SCRIPT, keys(),
                String.valueOf(System.currentTimeMillis()));
        if (result == null || result.isEmpty()) return Optional.empty();
        if ("MISSING".equals(String.valueOf(result.getFirst()))) {
            String missingId = result.size() > 1 ? String.valueOf(result.get(1)) : "unknown";
            redisTemplate.opsForList().leftPush(DEAD_LETTER_KEY, "missing-payload:" + missingId);
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(
                    String.valueOf(result.get(1)), WorkflowRecoveryCommand.class));
        } catch (Exception e) {
            String messageId = String.valueOf(result.getFirst());
            redisTemplate.execute(DEAD_LETTER_SCRIPT, keys("malformed:" + messageId), messageId,
                    "malformed:" + messageId, String.valueOf(System.currentTimeMillis()),
                    String.valueOf(messageTtlMs));
            log.warn("[WorkflowRecovery] malformed queue message moved to dead letter: id={}", messageId);
            return Optional.empty();
        }
    }

    public void acknowledge(WorkflowRecoveryCommand message) {
        redisTemplate.execute(ACK_SCRIPT, keys(), message.recoveryId());
    }

    public boolean retry(WorkflowRecoveryCommand message, String error, Duration delay) {
        WorkflowRecoveryCommand retry = message.nextAttempt(error);
        try {
            Long result = redisTemplate.execute(RETRY_SCRIPT, keys(), message.recoveryId(),
                    objectMapper.writeValueAsString(retry));
            return Long.valueOf(1L).equals(result);
        } catch (JsonProcessingException e) {
            deadLetter(message, "retry serialization failed: " + e.getMessage());
            return false;
        }
    }

    public void deadLetter(WorkflowRecoveryCommand message, String error) {
        WorkflowRecoveryCommand failed = message.withError(error);
        try {
            redisTemplate.opsForHash().put(MESSAGE_KEY, message.recoveryId(),
                    objectMapper.writeValueAsString(failed));
        } catch (JsonProcessingException ignored) {
            // Preserve the original payload when diagnostic enrichment fails.
        }
        redisTemplate.execute(DEAD_LETTER_SCRIPT, keys(generation(message)), message.recoveryId(),
                generation(message), String.valueOf(System.currentTimeMillis()),
                String.valueOf(messageTtlMs));
    }

    /** Requeues deliveries abandoned by a crashed recovery consumer. */
    public int reclaimTimedOut(Duration visibilityTimeout, int maxRetries, int limit) {
        long cutoff = System.currentTimeMillis() - visibilityTimeout.toMillis();
        Set<String> ids = redisTemplate.opsForZSet().rangeByScore(
                PROCESSING_KEY, Double.NEGATIVE_INFINITY, cutoff, 0, Math.max(1, limit));
        if (ids == null || ids.isEmpty()) return 0;
        int reclaimed = 0;
        for (String id : ids) {
            Object json = redisTemplate.opsForHash().get(MESSAGE_KEY, id);
            if (json == null) {
                redisTemplate.opsForZSet().remove(PROCESSING_KEY, id);
                continue;
            }
            try {
                WorkflowRecoveryCommand message = objectMapper.readValue(
                        String.valueOf(json), WorkflowRecoveryCommand.class);
                if (message.attempts() >= maxRetries) {
                    deadLetter(message, "recovery consumer visibility timeout exhausted");
                } else if (retry(message, "recovery consumer visibility timeout", Duration.ZERO)) {
                    reclaimed++;
                }
            } catch (Exception e) {
                redisTemplate.execute(DEAD_LETTER_SCRIPT, keys("malformed:" + id), id,
                        "malformed:" + id, String.valueOf(System.currentTimeMillis()),
                        String.valueOf(messageTtlMs));
            }
        }
        return reclaimed;
    }

    public long readySize() {
        Long size = redisTemplate.opsForList().size(READY_KEY);
        return size != null ? size : 0L;
    }

    public long deadLetterSize() {
        Long size = redisTemplate.opsForList().size(DEAD_LETTER_KEY);
        return size != null ? size : 0L;
    }

    private static List<String> keys() {
        return keys("unused");
    }

    private static List<String> keys(String generation) {
        return List.of(READY_KEY, PROCESSING_KEY, MESSAGE_KEY, DEDUP_KEY_PREFIX + generation,
                DEAD_LETTER_KEY, DEAD_GENERATION_KEY_PREFIX + generation);
    }

    private static String generation(WorkflowRecoveryCommand message) {
        return message.requestId() + ":" + message.checkpointUpdatedAtEpochMs()
                + ":" + message.trigger();
    }

    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> listScript(String source) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptText(source);
        script.setResultType(List.class);
        return script;
    }

    private static DefaultRedisScript<Long> longScript(String source) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(source);
        script.setResultType(Long.class);
        return script;
    }

}
