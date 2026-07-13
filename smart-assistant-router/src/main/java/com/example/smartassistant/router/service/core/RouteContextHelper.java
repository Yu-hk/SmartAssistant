/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.RouteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由上下文构建辅助服务。
 * <p>
 * 从 RouterService 拆出，负责构建路由上下文及从 Redis 加载会话历史。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-13
 */
@Service
public class RouteContextHelper {

    private static final Logger log = LoggerFactory.getLogger(RouteContextHelper.class);

    private final StringRedisTemplate redisTemplate;
    private final int maxHistoryMessages;

    public RouteContextHelper(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Value("${router.context.history.max-messages:10}") int maxHistoryMessages) {
        this.redisTemplate = redisTemplate;
        this.maxHistoryMessages = maxHistoryMessages;
    }

    /**
     * 构建上下文。
     * ⭐ 从 Redis 加载会话历史，提升多轮对话的路由准确性。
     */
    public Map<String, Object> buildContext(RouteRequest request) {
        Map<String, Object> context = new HashMap<>();
        String sessionId = request.getSessionId();
        if (sessionId != null) {
            context.put("sessionId", sessionId);
        }
        if (request.getUserId() != null) {
            context.put("userId", request.getUserId());
        }
        loadConversationHistoryFromRedis(context, sessionId);
        return context;
    }

    /**
     * 从 Redis 加载会话历史消息
     */
    private void loadConversationHistoryFromRedis(Map<String, Object> context, String sessionId) {
        if (redisTemplate == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            String historyKey = "chat:history:" + sessionId;
            Long size = redisTemplate.opsForList().size(historyKey);
            if (size == null || size == 0) {
                log.debug("[Router] Redis 中无会话历史: sessionId={}", sessionId);
                return;
            }
            int start = 0;
            int end = Math.min(maxHistoryMessages - 1, size.intValue() - 1);
            List<String> history = redisTemplate.opsForList().range(historyKey, start, end);
            if (history != null && !history.isEmpty()) {
                context.put("conversationHistory", history);
                log.debug("[Router] 从 Redis 加载会话历史: sessionId={}, messages={}",
                        sessionId, history.size());
            }
        } catch (Exception e) {
            log.warn("[Router] 从 Redis 加载会话历史失败: sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
    }
}
