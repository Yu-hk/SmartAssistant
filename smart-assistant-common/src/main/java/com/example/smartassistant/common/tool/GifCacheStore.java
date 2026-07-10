/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.tool;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared GIF cache store (替代 DataGifTool 的进程内静态 map)。
 * <p>
 * 作为独立的 Spring Bean 存在，供工具注册中心生成 GIF 后写入，
 * 以及业务模块消费端读取，避免进程内静态缓存与「独立服务」语义不兼容的问题。
 * 线程安全。
 * </p>
 */
@Component
public class GifCacheStore {

    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    /** 写入缓存（生成方调用） */
    public void put(String key, byte[] data) {
        cache.put(key, data);
    }

    /** 取出并移除缓存（消费方调用，保证一对一消费） */
    public byte[] consume(String key) {
        return cache.remove(key);
    }

    /** 返回当前全部缓存快照（消费方兜底读取） */
    public Map<String, byte[]> getAll() {
        return new HashMap<>(cache);
    }
}
