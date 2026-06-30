/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.config;

import com.example.smartassistant.common.cache.CacheVersionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Router 缓存版本配置——创建 {@link CacheVersionManager} 用于协调缓存一致性。
 * <p>
 * 当经验更新或路由表变更时，递增版本号通知 Consumer 侧缓存失效。
 * </p>
 */
@Configuration
public class CacheVersionConfig {

    @Bean
    public CacheVersionManager routerCacheVersionManager(StringRedisTemplate redisTemplate) {
        return new CacheVersionManager(
                () -> {
                    String val = redisTemplate.opsForValue().get(CacheVersionManager.VERSION_KEY);
                    return val != null ? Long.parseLong(val) : 0L;
                },
                version -> redisTemplate.opsForValue().set(
                        CacheVersionManager.VERSION_KEY,
                        String.valueOf(version))
        );
    }
}
