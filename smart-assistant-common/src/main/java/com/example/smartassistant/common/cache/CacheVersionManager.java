/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * 缓存版本管理器——协调 Router 和 Consumer 之间的缓存一致性。
 * <p>
 * Router 在路由策略发生变化时（如新增经验/更新路由表）递增版本号。
 * Consumer 在检查缓存有效性时对比版本号，版本过期则跳过缓存。
 * 通过 Redis 共享版本号，key 为 {@code a2a:cache:version}。
 * </p>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * // Router 端：策略变化时递增
 * cacheVersionManager.incrementVersion();
 *
 * // Consumer 端：缓存读取前检查
 * if (!cacheVersionManager.isVersionValid(cachedVersion)) {
 *     // 跳过缓存，重新生成
 * }
 * }</pre>
 */
public class CacheVersionManager {

    private static final Logger log = LoggerFactory.getLogger(CacheVersionManager.class);

    /** Redis 中存储缓存版本号的 key */
    public static final String VERSION_KEY = "a2a:cache:version";

    /** 版本默认 TTL（30 天，保证版本号持续有效） */
    private static final long VERSION_TTL_SECONDS = 2592000;

    /** 版本缓存时间（秒），避免每次查询都读 Redis */
    private static final long VERSION_CACHE_MS = 5000;

    private volatile long cachedVersion = -1;
    private volatile long lastFetchTime = 0;

    private final Supplier<Long> versionReader;
    private final java.util.function.Consumer<Long> versionWriter;

    /**
     * @param versionReader 从 Redis 读取当前版本号，不存在时返回 -1
     * @param versionWriter 将新版本号写入 Redis
     */
    public CacheVersionManager(Supplier<Long> versionReader,
                                java.util.function.Consumer<Long> versionWriter) {
        this.versionReader = versionReader;
        this.versionWriter = versionWriter;
    }

    /**
     * 获取当前缓存版本号（带本地缓存，最多 5 秒过期）。
     */
    public long getCurrentVersion() {
        long now = System.currentTimeMillis();
        if (cachedVersion < 0 || (now - lastFetchTime) > VERSION_CACHE_MS) {
            try {
                cachedVersion = versionReader.get();
                lastFetchTime = now;
            } catch (Exception e) {
                log.warn("[CacheVersion] 读取版本号失败: {}", e.getMessage());
            }
        }
        return cachedVersion < 0 ? 0 : cachedVersion;
    }

    /**
     * 递增缓存版本号（Router 端调用：经验更新/路由表变更时）。
     */
    public long incrementVersion() {
        long newVersion = getCurrentVersion() + 1;
        try {
            versionWriter.accept(newVersion);
            cachedVersion = newVersion;
            lastFetchTime = System.currentTimeMillis();
            log.info("[CacheVersion] 缓存版本已递增: v{}", newVersion);
        } catch (Exception e) {
            log.warn("[CacheVersion] 递增版本号失败: {}", e.getMessage());
        }
        return newVersion;
    }

    /**
     * 判断缓存的版本号是否仍然有效。
     *
     * @param cachedVersion 缓存条目存储时的版本号
     * @return true 如果版本匹配或无法获取版本号（降级为通过）
     */
    public boolean isVersionValid(long cachedVersion) {
        if (cachedVersion < 0) return true; // 旧版缓存无版本标记，默认通过
        long current = getCurrentVersion();
        return cachedVersion == current;
    }

    /**
     * 重置版本号（用于测试或手动清除所有缓存）。
     */
    public void resetVersion() {
        try {
            versionWriter.accept(0L);
            cachedVersion = -1;
            lastFetchTime = 0;
            log.info("[CacheVersion] 缓存版本已重置");
        } catch (Exception e) {
            log.warn("[CacheVersion] 重置版本号失败: {}", e.getMessage());
        }
    }
}
