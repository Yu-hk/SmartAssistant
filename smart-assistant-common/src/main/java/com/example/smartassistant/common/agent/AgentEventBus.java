package com.example.smartassistant.common.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * P1 Agent 执行事件总线。
 * <p>
 * 记录 Agent 执行过程中的状态转换事件到 Redis，支持审计和回放。
 * 使用 Redis List 结构，每个 Agent 执行实例一个列表。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
@Service
public class AgentEventBus {

    private static final Logger log = LoggerFactory.getLogger(AgentEventBus.class);

    private static final String EVENT_KEY_PREFIX = "a2a:agent-events:";
    private static final int MAX_EVENTS_PER_REQUEST = 200;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // 内存缓存（当 Redis 不可用时回退，每个请求最多保留 50 个事件）
    private final ConcurrentLinkedDeque<AgentExecutionState.StateTransition> memoryFallback
            = new ConcurrentLinkedDeque<>();

    public AgentEventBus(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * 发布状态转换事件。
     */
    public void publish(AgentExecutionState.StateTransition event) {
        if (event == null) return;

        try {
            String json = objectMapper.writeValueAsString(event);
            String key = EVENT_KEY_PREFIX + (event.requestId() != null ? event.requestId() : "unknown");

            if (redisTemplate != null) {
                redisTemplate.opsForList().leftPush(key, json);
                Long size = redisTemplate.opsForList().size(key);
                if (size != null && size > MAX_EVENTS_PER_REQUEST) {
                    redisTemplate.opsForList().trim(key, 0, MAX_EVENTS_PER_REQUEST - 1);
                }
            }

            // 内存回退
            memoryFallback.addFirst(event);
            if (memoryFallback.size() > MAX_EVENTS_PER_REQUEST) {
                memoryFallback.removeLast();
            }

            log.debug("[AgentEvent] {}: {} → {} ({})",
                    event.requestId(), event.from(), event.to(), event.event());

        } catch (JsonProcessingException e) {
            log.warn("[AgentEvent] 序列化失败: {}", e.getMessage());
        }
    }

    /**
     * 便捷方法：发布简单状态转换。
     */
    public void publishEvent(String requestId, String agentId,
                             AgentExecutionState.State from, AgentExecutionState.State to,
                             AgentExecutionState.EventType eventType, String summary,
                             long elapsedMs, int iteration) {
        publish(new AgentExecutionState.StateTransition(
                agentId, requestId, from, to, eventType, summary, elapsedMs, iteration, LocalDateTime.now()
        ));
    }

    /**
     * 获取指定请求的事件列表。
     */
    public List<AgentExecutionState.StateTransition> getEvents(String requestId) {
        List<AgentExecutionState.StateTransition> result = new ArrayList<>();

        if (redisTemplate != null && requestId != null) {
            try {
                List<String> raw = redisTemplate.opsForList().range(EVENT_KEY_PREFIX + requestId, 0, -1);
                if (raw != null) {
                    for (String json : raw) {
                        try {
                            result.add(objectMapper.readValue(json, AgentExecutionState.StateTransition.class));
                        } catch (Exception e) {
                            log.warn("[AgentEventBus] 解析状态转换事件失败: {}", e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[AgentEvent] 读取失败: {}", e.getMessage());
            }
        }

        // 内存回退补充
        if (result.isEmpty()) {
            memoryFallback.stream()
                    .filter(e -> requestId == null || requestId.equals(e.requestId()))
                    .limit(50)
                    .forEach(result::add);
        }

        return result;
    }

    /**
     * 清理过期事件（由 @Scheduled 定期调用）。
     */
    public void cleanup() {
        // 事件会在 24 小时后由 Redis TTL 自动清理（如果设置了 key 的 TTL）
        // 内存缓存自动淘汰
    }
}
