/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.tool;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.example.smartassistant.common.error.AgentErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Read-before-Edit 防护闸——确保 Agent 在修改资源前已经读取过该资源。
 * <p>
 * 参照 Claude Code 的 Read-before-Edit 设计：修改操作执行前必须先在本次会话中
 * 读取过目标资源，否则拒绝执行并提示模型先读。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 在 queryOrder 中标记已读
 * readGuard.markRead(orderId);
 *
 * // 在修改操作前检查
 * String error = readGuard.requireRead(orderId);
 * if (error != null) return error;
 * }</pre>
 */
@Component
public class ReadBeforeEditGuard {

    private static final Logger log = LoggerFactory.getLogger(ReadBeforeEditGuard.class);

    /** 已读资源缓存：key=orderId, value=timestamp, TTL=5分钟 */
    private final Cache<String, Long> readCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(10000)
            .recordStats()
            .build();

    /**
     * 标记资源为"已读"状态。
     * 调用方（如 queryOrder）在成功返回数据后调用此方法。
     */
    public void markRead(String resourceId, String resourceType) {
        if (resourceId == null || resourceId.isBlank()) return;
        String key = buildKey(resourceType, resourceId);
        readCache.put(key, System.currentTimeMillis());
        log.debug("[ReadGuard] 已标记已读: type={}, id={}", resourceType, resourceId);
    }

    /**
     * 检查资源是否已读。如果未读，返回 ToolResult 错误提示（引导模型先查询）。
     *
     * @param resourceId   资源 ID（如订单号）
     * @param resourceType 资源类型（如 "order"）
     * @param queryTool    查询该资源的工具名（用于引导 LLM 先查询）
     * @return null=已读可继续，非null=未读返回错误提示
     */
    public String requireRead(String resourceId, String resourceType, String queryTool) {
        if (resourceId == null || resourceId.isBlank()) {
            return null; // 无资源 ID，跳过检查
        }
        String key = buildKey(resourceType, resourceId);
        if (readCache.getIfPresent(key) == null) {
            log.warn("[ReadGuard] ❌ 未读即修改: type={}, id={}", resourceType, resourceId);
            return "⚠️ 操作被拦截：您尚未查询过该" + resourceType
                    + "的详细信息。为了安全，请先调用 " + queryTool
                    + " 查询并确认后再执行修改操作。";
        }
        return null; // OK
    }

    /** 构建缓存 key */
    private static String buildKey(String resourceType, String resourceId) {
        return resourceType + ":" + resourceId;
    }

    /** 获取缓存统计信息（调试用） */
    public String getStats() {
        var stats = readCache.stats();
        return String.format("ReadGuard{marked=%d, hitRate=%.2f}",
                stats.requestCount(), stats.hitRate());
    }
}
