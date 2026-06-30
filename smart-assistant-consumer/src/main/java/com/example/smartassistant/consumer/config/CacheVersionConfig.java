/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.config;

import com.example.smartassistant.common.cache.CacheVersionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * Consumer 缓存版本配置——创建 {@link CacheVersionManager} 用于校验缓存版本。
 * <p>
 * Consumer 在读取缓存前检查版本号，Router 递增版本后自动跳过过期缓存。
 * </p>
 */
@Configuration
public class CacheVersionConfig {

    @Bean
    public CacheVersionManager consumerCacheVersionManager(ReactiveStringRedisTemplate redisTemplate) {
        return new CacheVersionManager(
                () -> {
                    // 使用阻塞方式从 Reactive Redis 读取
                    try {
                        String val = redisTemplate.opsForValue()
                                .get(CacheVersionManager.VERSION_KEY)
                                .block(java.time.Duration.ofSeconds(2));
                        return val != null ? Long.parseLong(val) : 0L;
                    } catch (Exception e) {
                        return 0L;
                    }
                },
                version -> {
                    // Consumer 不写入版本
                }
        );
    }
}
