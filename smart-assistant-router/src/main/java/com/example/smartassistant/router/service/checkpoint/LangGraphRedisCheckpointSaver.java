package com.example.smartassistant.router.service.checkpoint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.AbstractCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.springframework.aop.framework.AopInfrastructureBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import com.example.smartassistant.router.service.recovery.WorkflowExecutionLeaseService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Redis-backed native LangGraph4j checkpoint saver with an in-memory test fallback. */
@Component
public class LangGraphRedisCheckpointSaver extends AbstractCheckpointSaver
        implements AopInfrastructureBean {

    /** All recovery keys share one hash tag so Lua remains Redis Cluster compatible. */
    public static final String KEY_PREFIX = "a2a:langgraph:{checkpoints}:data:";
    public static final String INDEX_KEY = "a2a:langgraph:{checkpoints}:updated";
    private static final String LEGACY_KEY_PREFIX = "a2a:langgraph:checkpoint:";
    private static final long TTL_SECONDS = 3_600;
    private static final long TTL_MILLIS = TimeUnit.SECONDS.toMillis(TTL_SECONDS);
    private static final DefaultRedisScript<Long> PERSIST_SCRIPT = script("""
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            redis.call('ZADD', KEYS[2], ARGV[3], ARGV[4])
            redis.call('PEXPIRE', KEYS[2], ARGV[2])
            return 1
            """);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = script("""
            redis.call('DEL', KEYS[1])
            redis.call('ZREM', KEYS[2], ARGV[1])
            return 1
            """);
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, LinkedList<Checkpoint>> fallback = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> fallbackUpdatedAt = new ConcurrentHashMap<>();
    private final AtomicBoolean legacyIndexBackfilled = new AtomicBoolean(false);
    private WorkflowExecutionLeaseService executionLeaseService;

    public LangGraphRedisCheckpointSaver(
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Autowired(required = false)
    void setExecutionLeaseService(WorkflowExecutionLeaseService executionLeaseService) {
        this.executionLeaseService = executionLeaseService;
    }

    @Override
    protected LinkedList<Checkpoint> loadCheckpoints(RunnableConfig config) throws Exception {
        String threadId = threadId(config);
        if (redisTemplate == null) {
            return new LinkedList<>(fallback.getOrDefault(threadId, new LinkedList<>()));
        }
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + threadId);
        boolean legacy = false;
        if (json == null || json.isBlank()) {
            json = redisTemplate.opsForValue().get(LEGACY_KEY_PREFIX + threadId);
            legacy = json != null && !json.isBlank();
        }
        if (json == null || json.isBlank()) return new LinkedList<>();
        List<CheckpointData> values = objectMapper.readValue(json, new TypeReference<>() {});
        LinkedList<Checkpoint> checkpoints = new LinkedList<>();
        for (CheckpointData value : values) checkpoints.add(value.toCheckpoint());
        if (legacy) {
            persistSerialized(threadId, objectMapper.writeValueAsString(values));
            redisTemplate.delete(LEGACY_KEY_PREFIX + threadId);
        }
        return checkpoints;
    }

    @Override
    protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints,
                                      Checkpoint checkpoint) throws Exception {
        persist(config, checkpoints);
    }

    @Override
    protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints,
                                     Checkpoint checkpoint) throws Exception {
        persist(config, checkpoints);
    }

    @Override
    protected Tag releaseCheckpoints(RunnableConfig config,
                                     LinkedList<Checkpoint> checkpoints) {
        String threadId = threadId(config);
        assertExecutionOwnership(threadId);
        if (redisTemplate == null) {
            fallback.remove(threadId);
            fallbackUpdatedAt.remove(threadId);
        } else {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(KEY_PREFIX + threadId, INDEX_KEY), threadId);
            redisTemplate.delete(LEGACY_KEY_PREFIX + threadId);
        }
        return new Tag(threadId, List.copyOf(checkpoints));
    }

    private void persist(RunnableConfig config, LinkedList<Checkpoint> checkpoints) throws Exception {
        String threadId = threadId(config);
        assertExecutionOwnership(threadId);
        if (redisTemplate == null) {
            fallback.put(threadId, new LinkedList<>(checkpoints));
            fallbackUpdatedAt.put(threadId, System.currentTimeMillis());
            return;
        }
        List<CheckpointData> values = checkpoints.stream().map(CheckpointData::from).toList();
        persistSerialized(threadId, objectMapper.writeValueAsString(values));
    }

    private void persistSerialized(String threadId, String json) {
        redisTemplate.execute(PERSIST_SCRIPT, List.of(KEY_PREFIX + threadId, INDEX_KEY),
                json, String.valueOf(TTL_MILLIS), String.valueOf(System.currentTimeMillis()), threadId);
    }

    /** Returns checkpoint ids whose durable state has not advanced since the cutoff. */
    public List<StaleCheckpoint> findStale(long cutoffEpochMs, int limit) {
        int safeLimit = Math.max(1, limit);
        if (redisTemplate == null) {
            return fallbackUpdatedAt.entrySet().stream()
                    .filter(entry -> entry.getValue() <= cutoffEpochMs)
                    .sorted(Map.Entry.comparingByValue())
                    .limit(safeLimit)
                    .map(entry -> new StaleCheckpoint(entry.getKey(), entry.getValue()))
                    .toList();
        }
        backfillLegacyIndex();
        Set<String> ids = redisTemplate.opsForZSet().rangeByScore(
                INDEX_KEY, Double.NEGATIVE_INFINITY, cutoffEpochMs, 0, safeLimit);
        if (ids == null || ids.isEmpty()) return List.of();
        List<StaleCheckpoint> stale = new ArrayList<>();
        for (String id : ids) {
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + id))) {
                redisTemplate.opsForZSet().remove(INDEX_KEY, id);
                continue;
            }
            Double score = redisTemplate.opsForZSet().score(INDEX_KEY, id);
            stale.add(new StaleCheckpoint(id, score != null ? score.longValue() : 0L));
        }
        return stale;
    }

    /** One-time rolling-upgrade migration; SCAN avoids blocking Redis like KEYS would. */
    private void backfillLegacyIndex() {
        if (!legacyIndexBackfilled.compareAndSet(false, true)) return;
        long now = System.currentTimeMillis();
        try (var cursor = redisTemplate.scan(ScanOptions.scanOptions()
                .match(LEGACY_KEY_PREFIX + "*").count(100).build())) {
            while (cursor.hasNext()) {
                String legacyKey = cursor.next();
                String json = redisTemplate.opsForValue().get(legacyKey);
                if (json == null || json.isBlank()) continue;
                String threadId = legacyKey.substring(LEGACY_KEY_PREFIX.length());
                Long remaining = redisTemplate.getExpire(legacyKey, TimeUnit.MILLISECONDS);
                long age = remaining != null && remaining > 0L
                        ? Math.max(0L, TTL_MILLIS - remaining) : TTL_MILLIS;
                persistSerialized(threadId, json);
                redisTemplate.opsForZSet().add(INDEX_KEY, threadId, now - age);
                redisTemplate.delete(legacyKey);
            }
        } catch (RuntimeException e) {
            legacyIndexBackfilled.set(false);
            throw e;
        }
    }

    public OptionalLong lastUpdated(String threadId) {
        if (threadId == null || threadId.isBlank()) return OptionalLong.empty();
        if (redisTemplate == null) {
            Long value = fallbackUpdatedAt.get(threadId);
            return value != null ? OptionalLong.of(value) : OptionalLong.empty();
        }
        Double score = redisTemplate.opsForZSet().score(INDEX_KEY, threadId);
        return score != null ? OptionalLong.of(score.longValue()) : OptionalLong.empty();
    }

    public record StaleCheckpoint(String threadId, long updatedAtEpochMs) {}

    private void assertExecutionOwnership(String threadId) {
        if (executionLeaseService != null) executionLeaseService.assertOwned(threadId);
    }

    private static DefaultRedisScript<Long> script(String source) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(source);
        script.setResultType(Long.class);
        return script;
    }

    private record CheckpointData(String id, Map<String, Object> state,
                                  String nodeId, String nextNodeId) {
        private static CheckpointData from(Checkpoint checkpoint) {
            return new CheckpointData(checkpoint.getId(),
                    new LinkedHashMap<>(checkpoint.getState()),
                    checkpoint.getNodeId(), checkpoint.getNextNodeId());
        }

        private Checkpoint toCheckpoint() {
            return Checkpoint.builder().id(id).state(state)
                    .nodeId(nodeId).nextNodeId(nextNodeId).build();
        }
    }
}
