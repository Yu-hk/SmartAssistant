/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis 缓存配置
 * 
 * <p>提供多级缓存策略：</p>
 * <ul>
 *     <li>routerResponse: Router 响应缓存（5分钟）</li>
 *     <li>agentResponse: Agent 响应缓存（10分钟）</li>
 *     <li>userProfile: 用户画像缓存（30分钟）</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class CacheConfig {
    
    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);
    
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // 默认配置
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))  // 默认 TTL 5分钟
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();  // 不缓存 null 值
        
        // 针对不同缓存的特定配置
        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
        
        // Router 响应缓存：5分钟
        cacheConfigs.put("routerResponse", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        
        // Agent 响应缓存：10分钟（Agent 结果相对稳定）
        cacheConfigs.put("agentResponse", defaultConfig.entryTtl(Duration.ofMinutes(10)));
        
        // 用户画像缓存：30分钟（用户偏好变化较慢）
        cacheConfigs.put("userProfile", defaultConfig.entryTtl(Duration.ofMinutes(30)));
        
        // 会话数据缓存：7天
        cacheConfigs.put("sessionData", defaultConfig.entryTtl(Duration.ofDays(7)));
        
        RedisCacheManager cacheManager = RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
        
        log.info("[Cache] Redis 缓存管理器初始化完成");
        log.info("[Cache] 缓存配置: routerResponse=5min, agentResponse=10min, userProfile=30min");
        
        return cacheManager;
    }
}
