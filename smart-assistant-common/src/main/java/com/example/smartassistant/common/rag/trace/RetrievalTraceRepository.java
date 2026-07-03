/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 检索链路追溯存储仓库 — 将 RetrievalTrace 存入 Redis 供线上查询。
 * <p>
 * Redis Key: {@code a2a:rag:trace:{requestId}}
 * TTL: {@link #TRACE_TTL_SECONDS}（默认 1 小时）
 * </p>
 */
public class RetrievalTraceRepository {

    private static final Logger log = LoggerFactory.getLogger(RetrievalTraceRepository.class);

    private static final String TRACE_KEY_PREFIX = "a2a:rag:trace:";
    private static final long TRACE_TTL_SECONDS = 3600; // 1 小时

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RetrievalTraceRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * 保存检索链路追溯。
     *
     * @param trace 追溯记录
     */
    public void save(RetrievalTrace trace) {
        if (redisTemplate == null || trace == null || trace.getRequestId() == null) return;

        try {
            String key = TRACE_KEY_PREFIX + trace.getRequestId();
            String json = objectMapper.writeValueAsString(trace);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(TRACE_TTL_SECONDS));
            log.debug("[RetrievalTrace] 已保存: {}", trace.toSummary());
        } catch (JsonProcessingException e) {
            log.warn("[RetrievalTrace] 序列化失败: requestId={}, error={}", trace.getRequestId(), e.getMessage());
        } catch (Exception e) {
            log.warn("[RetrievalTrace] 存储失败: requestId={}, error={}", trace.getRequestId(), e.getMessage());
        }
    }

    /**
     * 查询检索链路追溯。
     *
     * @param requestId 请求 ID
     * @return 追溯记录，不存在返回 null
     */
    public RetrievalTrace findByRequestId(String requestId) {
        if (redisTemplate == null || requestId == null) return null;

        try {
            String key = TRACE_KEY_PREFIX + requestId;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return null;
            return objectMapper.readValue(json, RetrievalTrace.class);
        } catch (Exception e) {
            log.warn("[RetrievalTrace] 查询失败: requestId={}, error={}", requestId, e.getMessage());
            return null;
        }
    }
}
