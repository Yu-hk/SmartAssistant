/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * ⭐ 二级缓存（Caffeine L1 + Redis L2）— 降低 Redis 延迟和网络开销。
 * <p>
 * 适用场景：高频率读取、低更新频率的数据，如语义缓存、路由决策缓存。
 * 当前项目中 {@code SemanticRouteCacheService} 的 T1/T2/T3 缓存层适用。
 * </p>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * L2Cache<String, String> cache = new L2Cache.Builder<String, String>()
 *     .l1MaxSize(500)
 *     .l1ExpireMinutes(10)
 *     .l2Loader(key -> redisTemplate.opsForValue().get(key))
 *     .build();
 *
 * String value = cache.get("cache-key"); // L1→L2→loader
 * cache.put("cache-key", "value");       // 同时写入 L1 + L2
 * }</pre>
 */
public class L2Cache<K, V> {

    private static final Logger log = LoggerFactory.getLogger(L2Cache.class);

    /** Caffeine L1 本地缓存 */
    private final Cache<K, V> l1Cache;

    /** L2 加载器（通常为 Redis GET 操作） */
    private final Function<K, V> l2Loader;

    /** L2 写入器（通常为 Redis SET 操作） */
    private final BiConsumer<K, V> l2Writer;

    /** 缓存名称（日志用） */
    private final String name;

    private L2Cache(Builder<K, V> builder) {
        Caffeine<Object, Object> caffeine = Caffeine.newBuilder()
                .maximumSize(builder.l1MaxSize)
                .expireAfterWrite(builder.l1ExpireMinutes, TimeUnit.MINUTES)
                .recordStats();
        if (builder.l1ExpireMinutes > 0) {
            caffeine.expireAfterWrite(builder.l1ExpireMinutes, TimeUnit.MINUTES);
        }
        this.l1Cache = caffeine.build();
        this.l2Loader = builder.l2Loader;
        this.l2Writer = builder.l2Writer;
        this.name = builder.name != null ? builder.name : "L2Cache";
        log.info("[L2Cache:{}] 初始化: L1 maxSize={}, expire={}min", name, builder.l1MaxSize, builder.l1ExpireMinutes);
    }

    /**
     * 获取缓存值（L1 → L2 → loader）。
     */
    public V get(K key) {
        if (key == null) return null;

        // L1 命中
        V value = l1Cache.getIfPresent(key);
        if (value != null) {
            return value;
        }

        // L2 加载
        if (l2Loader != null) {
            value = l2Loader.apply(key);
            if (value != null) {
                l1Cache.put(key, value); // 回填 L1
            }
        }

        return value;
    }

    /**
     * 写入缓存（同时写入 L1 + L2）。
     */
    public void put(K key, V value) {
        if (key == null) return;
        l1Cache.put(key, value);
        if (l2Writer != null) {
            try {
                l2Writer.accept(key, value);
            } catch (Exception e) {
                log.warn("[L2Cache:{}] L2 写入失败: key={}, error={}", name, key, e.getMessage());
            }
        }
    }

    /**
     * 使缓存失效。
     */
    public void invalidate(K key) {
        l1Cache.invalidate(key);
        // L2 通过 TTL 自动过期
    }

    /** 获取统计信息 */
    public CacheStats stats() {
        var stats = l1Cache.stats();
        return new CacheStats(stats.hitCount(), stats.missCount(),
                stats.hitRate(), l1Cache.estimatedSize());
    }

    /** 缓存统计 */
    public record CacheStats(long hitCount, long missCount, double hitRate, long estimatedSize) {
        @Override
        public String toString() {
            return String.format("hit=%d, miss=%d, rate=%.2f%%, size=%d",
                    hitCount, missCount, hitRate * 100, estimatedSize);
        }
    }

    /** L2 写入器函数接口 */
    @FunctionalInterface
    public interface BiConsumer<T, U> {
        void accept(T t, U u);
    }

    /** Builder */
    public static class Builder<K, V> {
        private int l1MaxSize = 1000;
        private int l1ExpireMinutes = 10;
        private Function<K, V> l2Loader;
        private BiConsumer<K, V> l2Writer;
        private String name;

        public Builder<K, V> l1MaxSize(int size) { this.l1MaxSize = size; return this; }
        public Builder<K, V> l1ExpireMinutes(int min) { this.l1ExpireMinutes = min; return this; }
        public Builder<K, V> l2Loader(Function<K, V> loader) { this.l2Loader = loader; return this; }
        public Builder<K, V> l2Writer(BiConsumer<K, V> writer) { this.l2Writer = writer; return this; }
        public Builder<K, V> name(String name) { this.name = name; return this; }
        public L2Cache<K, V> build() { return new L2Cache<>(this); }
    }
}
