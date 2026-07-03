/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * ⭐ 上下文压缩钩子接口 — 压缩前/后执行自定义逻辑。
 * <p>
 * 对应文章二 Hook 机制：
 * <ul>
 *   <li><b>Pre-Compact</b>：压缩前保存状态到外部系统、决定是否跳过</li>
 *   <li><b>Post-Compact</b>：压缩后记忆持久化、通知监控、校验质量</li>
 * </ul>
 * </p>
 */
public interface CompressionHooks {

    /** 压缩前回调 */
    default void beforeCompress(int messageCount, int targetCount) {}

    /** 压缩后回调 */
    default void afterCompress(int originalCount, int compressedCount, List<String> summaryChain) {}

    /**
     * 默认空实现。
     */
    static CompressionHooks noop() {
        return new CompressionHooks() {};
    }
}
