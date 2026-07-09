/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.heartbeat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * Agent 心跳服务。
 *
 * <p>子 Agent 定时上报心跳到 Redis，主 Agent 通过 {@link HeartbeatMonitor}
 * 定期检查心跳是否超时，超时的子 Agent 触发降级或重试。</p>
 *
 * <p>Redis Key 格式：{@code a2a:heartbeat:{requestId}:{agentName}}</p>
 * <p>Redis Value 格式：{@code status|progress|timestamp}</p>
 */
@Service
public class AgentHeartbeatService {

    private static final Logger LOG = LoggerFactory.getLogger(AgentHeartbeatService.class);

    private static final String HEARTBEAT_KEY_PREFIX = "a2a:heartbeat:";
    private static final long HEARTBEAT_TTL_SECONDS = 120;   // 心跳记录 TTL 2分钟
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;   // 默认超时阈值 30秒

    private final StringRedisTemplate redisTemplate;

    public AgentHeartbeatService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ═══════════════════════════════════════════════════════════
    // 子 Agent 心跳上报 API
    // ═══════════════════════════════════════════════════════════

    /**
     * 上报心跳。
     *
     * @param requestId  主任务 ID
     * @param agentName  子 Agent 名称
     * @param status     当前状态（RUNNING / COMPLETED / FAILED）
     * @param progress   进度描述（如 "3/5 步骤完成"）
     */
    public void beat(String requestId, String agentName, String status, String progress) {
        if (requestId == null || agentName == null) return;
        String key = HEARTBEAT_KEY_PREFIX + requestId + ":" + agentName;
        String value = status + "|" + (progress != null ? progress : "") + "|"
                + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(HEARTBEAT_TTL_SECONDS));
        LOG.debug("[Heartbeat] agent={}, requestId={}, status={}, progress={}",
                agentName, requestId, status, progress);
    }

    /**
     * 上报心跳（简易版，默认 RUNNING 状态）。
     */
    public void beat(String requestId, String agentName) {
        beat(requestId, agentName, "RUNNING", "");
    }

    /**
     * 标记 Agent 执行完成。
     */
    public void markCompleted(String requestId, String agentName) {
        beat(requestId, agentName, "COMPLETED", "");
    }

    /**
     * 标记 Agent 执行失败。
     */
    public void markFailed(String requestId, String agentName, String reason) {
        beat(requestId, agentName, "FAILED", reason);
    }

    // ═══════════════════════════════════════════════════════════
    // 主 Agent 查询 API
    // ═══════════════════════════════════════════════════════════

    /**
     * 获取某个子 Agent 的最新心跳。
     *
     * @param requestId 主任务 ID
     * @param agentName 子 Agent 名称
     * @return 心跳信息，无数据返回 null
     */
    public HeartbeatInfo getLastBeat(String requestId, String agentName) {
        String key = HEARTBEAT_KEY_PREFIX + requestId + ":" + agentName;
        String raw = redisTemplate.opsForValue().get(key);
        if (raw == null) return null;
        return HeartbeatInfo.fromString(agentName, raw);
    }

    /**
     * 获取主任务的所有活跃子 Agent 列表。
     */
    public Set<String> getActiveAgents(String requestId) {
        String pattern = HEARTBEAT_KEY_PREFIX + requestId + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) return Set.of();
        // 提取 agentName 部分（去掉前缀）
        java.util.HashSet<String> agents = new java.util.HashSet<>();
        String prefix = HEARTBEAT_KEY_PREFIX + requestId + ":";
        for (String key : keys) {
            agents.add(key.substring(prefix.length()));
        }
        return agents;
    }

    /**
     * 检查某个 Agent 是否超时。
     */
    public boolean isTimeout(String requestId, String agentName) {
        return isTimeout(requestId, agentName, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 检查某个 Agent 是否超时（自定义超时阈值）。
     */
    public boolean isTimeout(String requestId, String agentName, long timeoutSeconds) {
        HeartbeatInfo beat = getLastBeat(requestId, agentName);
        if (beat == null) return true;  // 无心跳视为超时
        Duration elapsed = Duration.between(beat.timestamp, LocalDateTime.now());
        return elapsed.getSeconds() > timeoutSeconds;
    }

    // ═══════════════════════════════════════════════════════════
    // 清除
    // ═══════════════════════════════════════════════════════════

    /**
     * 清除主任务的所有心跳记录（任务结束时调用）。
     */
    public void cleanUp(String requestId) {
        String pattern = HEARTBEAT_KEY_PREFIX + requestId + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            LOG.info("[Heartbeat] 清理心跳记录: requestId={}, count={}", requestId, keys.size());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 心跳信息模型
    // ═══════════════════════════════════════════════════════════

    /**
     * 心跳信息。
     */
    public static class HeartbeatInfo {
        public final String agentName;
        public final String status;
        public final String progress;
        public final LocalDateTime timestamp;

        HeartbeatInfo(String agentName, String status, String progress, LocalDateTime timestamp) {
            this.agentName = agentName;
            this.status = status;
            this.progress = progress;
            this.timestamp = timestamp;
        }

        static HeartbeatInfo fromString(String agentName, String raw) {
            if (raw == null) return null;
            String[] parts = raw.split("\\|", 3);
            String status = parts.length > 0 ? parts[0] : "UNKNOWN";
            String progress = parts.length > 1 ? parts[1] : "";
            LocalDateTime ts = parts.length > 2
                    ? LocalDateTime.parse(parts[2], DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    : LocalDateTime.now();
            return new HeartbeatInfo(agentName, status, progress, ts);
        }

        public boolean isCompleted() { return "COMPLETED".equals(status); }
        public boolean isFailed() { return "FAILED".equals(status); }
        public boolean isRunning() { return "RUNNING".equals(status); }

        public long elapsedSeconds() {
            return Duration.between(timestamp, LocalDateTime.now()).getSeconds();
        }
    }
}
