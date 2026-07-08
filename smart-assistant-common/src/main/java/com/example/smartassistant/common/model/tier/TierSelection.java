/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.model.tier;

import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;

/**
 * 一次档位路由的结果快照——用于日志、链路追踪与可观测性。
 *
 * @param requestedTier  按复杂度/意图选定的初始档位
 * @param servedTier     实际成功服务的档位（降级后可能低于 requestedTier）
 * @param servedModelName 实际服务的模型名
 * @param degraded       是否发生了降级
 * @param attemptedTiers 尝试过的档位顺序（含失败的）
 * @param reason         选择/降级原因
 * @param latencyMillis  总耗时（毫秒）
 * @param response       实际模型响应（成功时非 null）
 */
public record TierSelection(
        ModelTier requestedTier,
        ModelTier servedTier,
        String servedModelName,
        boolean degraded,
        List<ModelTier> attemptedTiers,
        String reason,
        long latencyMillis,
        ChatResponse response) {
}
