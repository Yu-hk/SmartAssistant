/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.memory;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * ChatMemoryAutoConfiguration Bean 选择逻辑测试：
 * <ul>
 *   <li>默认 → InMemoryChatMemory；</li>
 *   <li>type=redis 且有 StringRedisTemplate → RedisChatMemory；</li>
 *   <li>type=redis 但无 Redis 模板 → 降级 InMemoryChatMemory。</li>
 * </ul>
 */
class ChatMemoryAutoConfigurationTest {

    @Configuration
    static class RedisTemplateConfig {
        @Bean
        StringRedisTemplate stringRedisTemplate() {
            // 仅用于 Bean 装配验证，不建立真实连接（mock 连接工厂避免 afterPropertiesSet 失败）
            return new StringRedisTemplate(mock(RedisConnectionFactory.class));
        }
    }

    @Test
    void defaultShouldUseInMemory() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(ChatMemoryAutoConfiguration.class);
            ctx.refresh();
            ChatMemory memory = ctx.getBean(ChatMemory.class);
            assertInstanceOf(InMemoryChatMemory.class, memory,
                    "未配置 redis 时应使用 InMemoryChatMemory");
        }
    }

    @Test
    void redisTypeWithTemplateShouldUseRedis() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(ChatMemoryAutoConfiguration.class, RedisTemplateConfig.class);
            ctx.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("test", Map.of("chat.memory.type", "redis")));
            ctx.refresh();
            ChatMemory memory = ctx.getBean(ChatMemory.class);
            assertInstanceOf(RedisChatMemory.class, memory,
                    "type=redis 且存在 StringRedisTemplate 时应使用 RedisChatMemory");
        }
    }

    @Test
    void redisTypeWithoutTemplateShouldFallbackToInMemory() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(ChatMemoryAutoConfiguration.class);
            ctx.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("test", Map.of("chat.memory.type", "redis")));
            ctx.refresh();
            ChatMemory memory = ctx.getBean(ChatMemory.class);
            assertInstanceOf(InMemoryChatMemory.class, memory,
                    "type=redis 但无 StringRedisTemplate 时应降级为 InMemoryChatMemory");
        }
    }

    @Test
    void missingBeanAllowsOverride() {
        // 下游模块自定义 ChatMemory 时，自动配置应让位（@ConditionalOnMissingBean）。
        // 注册顺序：自定义配置先于自动配置，保证条件判定时自定义 Bean 已存在。
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(CustomMemoryConfig.class, ChatMemoryAutoConfiguration.class);
            ctx.refresh();
            ChatMemory memory = ctx.getBean(ChatMemory.class);
            assertTrue(memory instanceof CustomMemory, "自定义 ChatMemory 应覆盖自动配置");
        }
    }

    @Configuration
    static class CustomMemoryConfig {
        @Bean
        ChatMemory customChatMemory() {
            return new CustomMemory();
        }
    }

    static class CustomMemory implements ChatMemory {
        @Override
        public void add(String conversationId, org.springframework.ai.chat.messages.Message message) {}
        @Override
        public java.util.List<org.springframework.ai.chat.messages.Message> get(String conversationId, int lastN) {
            return java.util.List.of();
        }
        @Override
        public void clear(String conversationId) {}
    }
}
