/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Gateway 配置类
 * 
 * <p>路由配置已迁移到 application.yml，此类管理限流等扩展配置。</p>
 * <p>
 * <b>限流改进</b>：启用 Redis-based RequestRateLimiter。
 * 使用 {@link KeyResolver} 按客户端 IP + 用户 ID 组合限流，
 * 配合 {@link RedisRateLimiter} 实现分布式限流。
 * </p>
 */
@Configuration
@ConfigurationProperties(prefix = "gateway.rate-limit")
@Slf4j
public class GatewayConfig {

    private boolean enabled = false;
    private int defaultReplenishRate = 10;
    private int defaultBurstCapacity = 20;

    @Bean
    public RedisRateLimiter redisRateLimiter() {
        if (!enabled) {
            log.info("[RateLimit] ⚠️ 限流未启用（gateway.rate-limit.enabled=false）");
            return new RedisRateLimiter(Integer.MAX_VALUE, Integer.MAX_VALUE); // 不限制
        }
        RedisRateLimiter rateLimiter = new RedisRateLimiter(defaultReplenishRate, defaultBurstCapacity);
        log.info("[RateLimit] ✅ 限流已启用: replenishRate={}, burstCapacity={}",
                defaultReplenishRate, defaultBurstCapacity);
        return rateLimiter;
    }

    /**
     * 按客户端 IP + 用户 ID 组合限流。
     * 同时携带用户 ID 时按用户限流，否则按 IP 限流。
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                return Mono.just("user:" + userId);
            }
            String ip = exchange.getRequest().getRemoteAddress() != null
                    ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                    : "unknown";
            return Mono.just("ip:" + ip);
        };
    }

    // ==================== Configuration Properties ====================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefaultReplenishRate() {
        return defaultReplenishRate;
    }

    public void setDefaultReplenishRate(int defaultReplenishRate) {
        this.defaultReplenishRate = defaultReplenishRate;
    }

    public int getDefaultBurstCapacity() {
        return defaultBurstCapacity;
    }

    public void setDefaultBurstCapacity(int defaultBurstCapacity) {
        this.defaultBurstCapacity = defaultBurstCapacity;
    }
}
