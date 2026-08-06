/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 * Licensed under the MIT License.
 */
package com.example.smartassistant.common.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides Redis-backed chat memory when available and an in-memory fallback otherwise. */
@Configuration
public class ChatMemoryAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryAutoConfiguration.class);

    @Bean("smartAssistantChatMemory")
    @ConditionalOnMissingBean(ChatMemory.class)
    public ChatMemory smartAssistantChatMemory(
            ApplicationContext applicationContext,
            @Value("${chat.memory.type:inmemory}") String type,
            @Value("${chat.memory.max-messages:100}") int maxMessages,
            @Value("${chat.memory.ttl-seconds:0}") long ttlSeconds) {

        if ("redis".equalsIgnoreCase(type)) {
            ChatMemory redisMemory = createRedisMemoryIfAvailable(
                    applicationContext, maxMessages, ttlSeconds);
            if (redisMemory != null) {
                log.info("[ChatMemory] Registered RedisChatMemory (max={}, ttl={}s)",
                        maxMessages, ttlSeconds);
                return redisMemory;
            }
            log.warn("[ChatMemory] Redis support unavailable, falling back to InMemoryChatMemory");
        } else {
            log.info("[ChatMemory] Registered InMemoryChatMemory (max={})", maxMessages);
        }
        return new InMemoryChatMemory(Math.max(1, maxMessages));
    }

    private ChatMemory createRedisMemoryIfAvailable(
            ApplicationContext applicationContext, int maxMessages, long ttlSeconds) {
        try {
            Class<?> templateType = Class.forName(
                    "org.springframework.data.redis.core.StringRedisTemplate",
                    false,
                    applicationContext.getClassLoader());
            String[] beanNames = applicationContext.getBeanNamesForType(templateType);
            if (beanNames.length == 0) {
                return null;
            }
            Object redisTemplate = applicationContext.getBean(beanNames[0]);
            Class<?> memoryType = Class.forName(
                    "com.example.smartassistant.common.memory.RedisChatMemory",
                    true,
                    applicationContext.getClassLoader());
            return (ChatMemory) memoryType
                    .getConstructor(templateType, int.class, long.class)
                    .newInstance(redisTemplate, Math.max(1, maxMessages), Math.max(0, ttlSeconds));
        } catch (ClassNotFoundException e) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warn("[ChatMemory] Redis memory initialization failed: {}", e.getMessage());
            return null;
        }
    }
}
