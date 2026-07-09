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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RAG 全阶段 trace 记录器 —— 以真实请求 {@code requestId} 关联 RETRIEVAL / GENERATION / REJECTION 阶段。
 * <p>
 * 设计要点（对标文章《RAG 系统从 Demo 到生产》"质量问题定位到阶段"）：
 * <ul>
 *   <li><b>请求级关联</b>：用 Consumer/Router 下发的真实 {@code requestId} 串联各阶段，而非合成 ID。</li>
 *   <li><b>阶段标签化</b>：每个跨度带 {@link RagStage} 枚举与状态（OK/REJECTED/ERROR/SKIPPED）。</li>
 *   <li><b>零依赖降级</b>：{@code StringRedisTemplate} 为 null 时退化为纯内存（最新 N 条），不抛异常。</li>
 * </ul>
 *
 * <p>Redis Key: {@code a2a:stage:trace:{requestId}}，TTL 默认 1 小时。</p>
 */
public class StageTraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(StageTraceRecorder.class);

    private static final String TRACE_KEY_PREFIX = "a2a:stage:trace:";
    private static final long TRACE_TTL_SECONDS = 3600; // 1 小时

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 内存最新 trace（Redis 不可用或测试时仍可读） */
    private final Map<String, RagStageTrace> inMemory = new ConcurrentHashMap<>();

    public StageTraceRecorder(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * 获取或创建某请求的 stage trace（requestId 为 null 时自动生成）。
     */
    public RagStageTrace getOrCreate(String requestId, String query, String agentName) {
        if (requestId == null || requestId.isBlank()) {
            requestId = "trace-" + System.nanoTime();
        }
        return inMemory.computeIfAbsent(requestId, k -> new RagStageTrace(k, query, agentName));
    }

    /**
     * 记录一个阶段跨度。
     */
    public void recordStage(String requestId, RagStage stage, String status,
                            long durationMs, Map<String, Object> metrics) {
        if (requestId == null) return;
        getOrCreate(requestId, null, null).addStage(StageSpan.of(stage, durationMs, status, metrics));
    }

    /**
     * 标记无证据拒答（同时追加 REJECTION 阶段）。
     */
    public void markRejection(String requestId, String code, String message) {
        if (requestId == null) return;
        getOrCreate(requestId, null, null).markRejection(code, message);
    }

    /**
     * 持久化（Redis 可用时写入；始终保留内存副本）。
     */
    public void save(String requestId) {
        RagStageTrace trace = inMemory.get(requestId);
        if (trace == null) return;

        if (redisTemplate != null) {
            try {
                String key = TRACE_KEY_PREFIX + requestId;
                String json = objectMapper.writeValueAsString(trace);
                redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(TRACE_TTL_SECONDS));
                log.debug("[StageTrace] 已保存: {}", trace.toSummary());
            } catch (JsonProcessingException e) {
                log.warn("[StageTrace] 序列化失败: requestId={}, error={}", requestId, e.getMessage());
            } catch (Exception e) {
                log.warn("[StageTrace] 持久化失败（保留内存）: requestId={}, error={}", requestId, e.getMessage());
            }
        }
    }

    /**
     * 查询某请求的 stage trace（优先 Redis，回退内存）。
     */
    public RagStageTrace findByRequestId(String requestId) {
        if (requestId == null) return null;

        if (redisTemplate != null) {
            try {
                String json = redisTemplate.opsForValue().get(TRACE_KEY_PREFIX + requestId);
                if (json != null) {
                    return objectMapper.readValue(json, RagStageTrace.class);
                }
            } catch (Exception e) {
                log.warn("[StageTrace] 查询失败（回退内存）: requestId={}, error={}", requestId, e.getMessage());
            }
        }
        return inMemory.get(requestId);
    }
}
