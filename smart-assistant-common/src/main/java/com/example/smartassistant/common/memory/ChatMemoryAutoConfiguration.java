/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * ⭐ ChatMemory Spring Bean 自动配置。
 * <p>
 * 统一收敛会话记忆的实现选择，避免各业务模块重复 new 实例：
 * <ul>
 *   <li><b>默认（{@code chat.memory.type=inmemory} 或未配置）</b>：注册 {@link InMemoryChatMemory}；</li>
 *   <li><b>{@code chat.memory.type=redis} 且容器中存在 {@link StringRedisTemplate}</b>：
 *       注册 {@link RedisChatMemory}（生产多实例共享记忆推荐）；</li>
 *   <li>若配置为 redis 但无 Redis 连接（未引入 data-redis 或连接工厂缺失），
 *       自动降级为内存实现，保证应用可启动。</li>
 * </ul>
 * 使用 {@link ConditionalOnMissingBean} 允许下游模块（如 consumer）自定义覆盖。
 * </p>
 */
@Configuration
public class ChatMemoryAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(ChatMemory.class)
    public ChatMemory chatMemory(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Value("${chat.memory.type:inmemory}") String type,
            @Value("${chat.memory.max-messages:100}") int maxMessages,
            @Value("${chat.memory.ttl-seconds:0}") long ttlSeconds) {

        boolean redisEnabled = "redis".equalsIgnoreCase(type) && redisTemplate != null;
        if (redisEnabled) {
            log.info("[ChatMemory] 注册 RedisChatMemory (max={}, ttl={}s)", maxMessages, ttlSeconds);
            return new RedisChatMemory(redisTemplate, Math.max(1, maxMessages), Math.max(0, ttlSeconds));
        }

        if ("redis".equalsIgnoreCase(type) && redisTemplate == null) {
            log.warn("[ChatMemory] 配置为 redis 但容器中无 StringRedisTemplate，降级为 InMemoryChatMemory");
        } else {
            log.info("[ChatMemory] 注册 InMemoryChatMemory (max={})", maxMessages);
        }
        return new InMemoryChatMemory(Math.max(1, maxMessages));
    }
}
