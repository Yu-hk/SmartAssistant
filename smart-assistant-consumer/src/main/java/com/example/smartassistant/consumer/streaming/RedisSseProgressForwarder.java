package com.example.smartassistant.consumer.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

/** Forwards Router progress while leaving the terminal SSE event to Consumer. */
public final class RedisSseProgressForwarder {
    private static final Logger log = LoggerFactory.getLogger(RedisSseProgressForwarder.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RedisSseProgressForwarder(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public boolean forwardList(SseEventBus bus, String eventsKey) {
        try {
            while (true) {
                String payload = redis.opsForList().leftPop(eventsKey);
                if (payload == null) break;
                String type = extractType(payload);
                if (!"done".equals(type)) {
                    bus.send(SseEvent.raw(type, payload));
                }
            }
            return true;
        } catch (Exception error) {
            log.error("[StreamChat] Redis 事件转发失败: {}", error.getMessage());
            return false;
        }
    }

    public void forwardStream(SseEventBus bus, String streamKey, Cursor cursor) {
        if (redis == null) return;
        try {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                    .range(streamKey, Range.leftOpen(cursor.lastRecordId, "+"));
            if (records == null || records.isEmpty()) return;
            for (MapRecord<String, Object, Object> record : records) {
                cursor.lastRecordId = record.getId().getValue();
                Object rawPayload = record.getValue().get("payload");
                if (rawPayload == null) continue;
                String payload = String.valueOf(rawPayload);
                String type = extractType(payload);
                if (!"done".equals(type)) {
                    bus.send(SseEvent.raw(type, payload));
                    cursor.forwardedAny = true;
                }
            }
        } catch (Exception error) {
            log.debug("[StreamChat] Redis Stream 增量转发失败: key={}, error={}",
                    streamKey, error.getMessage());
        }
    }

    private String extractType(String payload) {
        try {
            Map<?, ?> event = json.readValue(payload, Map.class);
            Object type = event.get("type");
            return type instanceof String value ? value : "";
        } catch (Exception error) {
            return "";
        }
    }

    public static final class Cursor {
        private String lastRecordId = "0-0";
        private boolean forwardedAny;

        public boolean forwardedAny() {
            return forwardedAny;
        }
    }
}
