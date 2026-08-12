package com.example.smartassistant.router.service.checkpoint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.AbstractCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Redis-backed native LangGraph4j checkpoint saver with an in-memory test fallback. */
@Component
public class LangGraphRedisCheckpointSaver extends AbstractCheckpointSaver {

    private static final String KEY_PREFIX = "a2a:langgraph:checkpoint:";
    private static final long TTL_SECONDS = 3_600;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, LinkedList<Checkpoint>> fallback = new ConcurrentHashMap<>();

    public LangGraphRedisCheckpointSaver(
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected LinkedList<Checkpoint> loadCheckpoints(RunnableConfig config) throws Exception {
        String threadId = threadId(config);
        if (redisTemplate == null) {
            return new LinkedList<>(fallback.getOrDefault(threadId, new LinkedList<>()));
        }
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + threadId);
        if (json == null || json.isBlank()) return new LinkedList<>();
        List<CheckpointData> values = objectMapper.readValue(json, new TypeReference<>() {});
        LinkedList<Checkpoint> checkpoints = new LinkedList<>();
        for (CheckpointData value : values) checkpoints.add(value.toCheckpoint());
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
        if (redisTemplate == null) fallback.remove(threadId);
        else redisTemplate.delete(KEY_PREFIX + threadId);
        return new Tag(threadId, List.copyOf(checkpoints));
    }

    private void persist(RunnableConfig config, LinkedList<Checkpoint> checkpoints) throws Exception {
        String threadId = threadId(config);
        if (redisTemplate == null) {
            fallback.put(threadId, new LinkedList<>(checkpoints));
            return;
        }
        List<CheckpointData> values = checkpoints.stream().map(CheckpointData::from).toList();
        redisTemplate.opsForValue().set(KEY_PREFIX + threadId,
                objectMapper.writeValueAsString(values), TTL_SECONDS, TimeUnit.SECONDS);
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
