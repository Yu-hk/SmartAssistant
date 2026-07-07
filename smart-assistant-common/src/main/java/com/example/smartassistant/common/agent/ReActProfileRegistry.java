/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 入口级 ReAct 画像注册表。
 *
 * <p>由 {@link ReActProfileAutoConfiguration} 从配置属性构建；调用方通过
 * {@link #get(String)} 按入口 key 获取画像，缺失时返回 {@code null}
 * （由 {@link SmartReActAgent} 决定是否回退默认），或用
 * {@link #getOrDefault(String, ReActProfile)} 直接回退。</p>
 */
public class ReActProfileRegistry {

    private final Map<String, ReActProfile> profiles;

    public ReActProfileRegistry(Map<String, ReActProfile> profiles) {
        this.profiles = profiles == null ? new HashMap<>() : new HashMap<>(profiles);
    }

    /** 返回指定入口画像；不存在返回 {@code null}。 */
    public ReActProfile get(String key) {
        return profiles.get(key);
    }

    public boolean containsKey(String key) {
        return profiles.containsKey(key);
    }

    public ReActProfile getOrDefault(String key, ReActProfile fallback) {
        return profiles.getOrDefault(key, fallback);
    }

    public Map<String, ReActProfile> all() {
        return Collections.unmodifiableMap(profiles);
    }
}
