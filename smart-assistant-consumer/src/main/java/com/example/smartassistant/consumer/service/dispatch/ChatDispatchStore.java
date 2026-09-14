package com.example.smartassistant.consumer.service.dispatch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Durable attempt fence. Never take over RUNNING work automatically: it may have written business data. */
@Service
public class ChatDispatchStore {
    private static final long TTL_SECONDS = 86400;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private static final DefaultRedisScript<String> RESERVE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              if redis.call('HGET', KEYS[1], 'fingerprint') ~= ARGV[1] then return 'CONFLICT' end
              return redis.call('HGET', KEYS[1], 'command')
            end
            local previousKey = redis.call('GET', KEYS[2])
            if previousKey then
              local previousCommand = redis.call('HGET', previousKey, 'command')
              if previousCommand and redis.call('HGET', previousKey, 'status') == 'QUEUED'
                  and tonumber(cjson.decode(previousCommand).expiresAt) <= tonumber(ARGV[6]) then
                redis.call('HSET', previousKey, 'status', 'EXPIRED', 'result', ARGV[5])
                redis.call('DEL', KEYS[2])
              else return 'BUSY' end
            end
            redis.call('HSET', KEYS[1], 'fingerprint', ARGV[1], 'command', ARGV[2], 'status', 'QUEUED')
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[4])
            return ARGV[2]
            """, String.class);
    private static final DefaultRedisScript<String> START = new DefaultRedisScript<>("""
            local status = redis.call('HGET', KEYS[1], 'status')
            if not status then return 'MISSING' end
            if redis.call('HGET', KEYS[1], 'command') ~= ARGV[1] then return 'CONFLICT' end
            if status ~= 'QUEUED' then return status end
            if tonumber(ARGV[2]) >= tonumber(ARGV[3]) then return 'EXPIRED' end
            redis.call('HSET', KEYS[1], 'status', 'RUNNING')
            return 'ACQUIRED'
            """, String.class);
    private static final DefaultRedisScript<Long> FINISH = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'status') ~= ARGV[1] then return 0 end
            redis.call('HSET', KEYS[1], 'status', ARGV[2], 'result', ARGV[3])
            if ARGV[2] ~= 'UNCERTAIN' and redis.call('GET', KEYS[2]) == ARGV[4] then
              redis.call('DEL', KEYS[2])
            end
            return 1
            """, Long.class);

    public ChatDispatchStore(StringRedisTemplate redis, ObjectMapper mapper) { this.redis = redis; this.mapper = mapper; }

    public ChatDispatchCommand reserve(ChatDispatchCommand command) {
        String fingerprint = digest(json(List.of(command.userId(), command.sessionId(), command.question())));
        String result = redis.execute(RESERVE, keys(command), fingerprint, json(command), key(command.requestId()), Long.toString(TTL_SECONDS),
                json(PriorityRoutingDispatcher.failure("QUEUE_TIMEOUT", "排队超时，本轮尚未执行。")), Long.toString(System.currentTimeMillis()));
        if ("CONFLICT".equals(result)) throw new IllegalArgumentException("Request ID belongs to different input or identity");
        if ("BUSY".equals(result)) throw new IllegalStateException("当前账号已有请求在排队或执行，请等待该请求完成。");
        if (result == null) throw new IllegalStateException("Dispatch reservation unavailable");
        try { return mapper.readValue(result, ChatDispatchCommand.class); }
        catch (Exception error) { throw new IllegalStateException("Invalid dispatch reservation", error); }
    }

    public String start(ChatDispatchCommand command, long now) {
        String status = redis.execute(START, List.of(key(command.requestId())), json(command), Long.toString(now), Long.toString(command.expiresAt()));
        if (status == null) throw new IllegalStateException("Dispatch claim unavailable");
        return status;
    }

    public boolean finish(ChatDispatchCommand command, String expected, String status, Map<String, Object> result) {
        return Long.valueOf(1).equals(redis.execute(FINISH, keys(command), expected, status, json(result), key(command.requestId())));
    }

    public Map<String, Object> result(ChatDispatchCommand command) {
        Object result = redis.opsForHash().get(key(command.requestId()), "result");
        if (result == null) return null;
        try { return mapper.readValue(result.toString(), new TypeReference<>() {}); }
        catch (Exception error) { throw new IllegalStateException("Invalid dispatch result", error); }
    }

    public String status(ChatDispatchCommand command) {
        Object status = redis.opsForHash().get(key(command.requestId()), "status");
        return status == null ? "MISSING" : status.toString();
    }

    public ChatDispatchCommand ownedCommand(Long userId, String requestId) {
        Object value = redis.opsForHash().get(key(requestId), "command");
        if (value == null) return null;
        try {
            ChatDispatchCommand command = mapper.readValue(value.toString(), ChatDispatchCommand.class);
            if (!command.userId().equals(userId)) throw new SecurityException("Dispatch belongs to another user");
            return command;
        } catch (SecurityException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("Invalid dispatch command", error); }
    }

    private List<String> keys(ChatDispatchCommand command) {
        // One cluster hash slot permits atomic request binding plus per-user admission.
        return List.of(key(command.requestId()), "chat:dispatch:v1:{dispatch}:user:" + command.userId());
    }
    private String key(String requestId) { return "chat:dispatch:v1:{dispatch}:request:" + digest(requestId); }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalArgumentException("Invalid dispatch JSON", error); }
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalArgumentException("Invalid dispatch identity", error); }
    }
}
