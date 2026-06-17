/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent Client - 调用各 Agent 服务的 SSE 流式接口
 * <p>
 * 从 Redis 动态获取 Agent SSE URL 映射（由 Router AgentDiscoveryService 根据 Nacos 注册信息写入）
 * key: a2a:agent:sse:urls
 * value: {"location_weather": "<a href="http://192.168.0.101:8085/travel/stream/chat">...</a>", ...}
 * <p>
 * 服务上下线时自动更新 Redis，无需硬编码任何 agent 名称或地址。
 */
@Component
public class AgentStreamClient {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamClient.class);
    private static final String SSE_URLS_REDIS_KEY = "a2a:agent:sse:urls";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, String> agentUrlCache = new ConcurrentHashMap<>();
    private long lastRefreshTime = 0;
    private static final long REFRESH_INTERVAL_MS = 30000;

    public AgentStreamClient(@Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        // 尝试一次加载，不阻塞启动；后续由 ensureCacheFresh() 在后台重试
        refreshAgentUrls();
        if (!agentUrlCache.isEmpty()) {
            log.info("[AgentStreamClient] Agent URL 映射加载完成，共 {} 个 Agent", agentUrlCache.size());
        } else {
            log.warn("[AgentStreamClient] 启动时未加载到 Agent URL 映射，将在后台继续重试");
        }
    }

    private void refreshAgentUrls() {
        if (redisTemplate == null) {
            log.warn("[AgentStreamClient] Redis 未配置，无法加载 Agent URL 映射");
            return;
        }
        try {
            String json = redisTemplate.opsForValue().get(SSE_URLS_REDIS_KEY);
            if (json != null && !json.isBlank()) {
                Map<String, String> urls = objectMapper.readValue(json, new TypeReference<>() {
                });
                agentUrlCache.clear();
                agentUrlCache.putAll(urls);
                lastRefreshTime = System.currentTimeMillis();
                log.info("[AgentStreamClient] ✅ 从 Redis 加载 Agent URL 映射: {}", agentUrlCache);
            } else {
                log.warn("[AgentStreamClient] Redis 中无 Agent URL 映射 (key={})", SSE_URLS_REDIS_KEY);
            }
        } catch (Exception e) {
            log.warn("[AgentStreamClient] 从 Redis 加载 Agent URL 映射失败: {}", e.getMessage());
        }
    }

    private void ensureCacheFresh() {
        // 如果缓存为空，立即重试（不等待 REFRESH_INTERVAL）
        if (agentUrlCache.isEmpty()) {
            refreshAgentUrls();
            return;
        }
        if (System.currentTimeMillis() - lastRefreshTime > REFRESH_INTERVAL_MS) {
            refreshAgentUrls();
        }
    }

    /**
     * 获取指定 Agent 的 SSE 流完整 URL（直接从 Redis 返回完整地址）
     */
    public String getStreamUrl(String agentName) {
        ensureCacheFresh();
        String url = agentUrlCache.get(agentName);
        if (url == null) {
            log.warn("[AgentStreamClient] 未知 Agent: {}, 可用: {}", agentName, agentUrlCache.keySet());
            url = agentUrlCache.entrySet().stream()
                    .filter(e -> agentName.contains(e.getKey()) || e.getKey().contains(agentName))
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(null);
        }
        return url;
    }

    public boolean isStreamingSupported(String agentName) {
        ensureCacheFresh();
        if (agentUrlCache.containsKey(agentName)) return true;
        return agentUrlCache.keySet().stream()
                .anyMatch(key -> agentName.contains(key) || key.contains(agentName));
    }

}