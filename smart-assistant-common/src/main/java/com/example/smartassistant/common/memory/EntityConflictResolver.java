/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.memory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 实体记忆冲突解决器——检测并解决 Agent 记忆中的实体信息冲突。
 * <p>
 * 参照 AI 记忆系统设计文章：时间元数据 + 冲突解决机制 + 清理。
 * 当用户在不同轮次提供了矛盾的实体信息（如地址变更、订单状态矛盾），
 * 系统通过此组件检测冲突并自动解决。
 * </p>
 *
 * <h3>冲突检测流程</h3>
 * <pre>
 * update(sessionId, "shipping_address", "ORD-001", "北京")
 *   → cache miss → 记录 "北京" → 返回 noConflict
 *
 * update(sessionId, "shipping_address", "ORD-001", "上海")
 *   → cache hit: old="北京", new="上海"
 *   → NEWER_WINS → 更新为 "上海" → 返回 conflict + description
 * </pre>
 */
public class EntityConflictResolver {

    private static final Logger log = LoggerFactory.getLogger(EntityConflictResolver.class);

    /** 实体状态：按 (sessionId, entityType, entityId) 记录 */
    private final Cache<String, Map<String, EntityState>> sessionCache;

    /** 默认解决策略 */
    private final ConflictResolution defaultResolution;

    public EntityConflictResolver() {
        this(ConflictResolution.NEWER_WINS);
    }

    public EntityConflictResolver(ConflictResolution defaultResolution) {
        this.sessionCache = Caffeine.newBuilder()
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .maximumSize(5000)
                .build();
        this.defaultResolution = defaultResolution;
    }

    /**
     * 更新实体信息并检测冲突。
     *
     * @param sessionId  会话 ID
     * @param entityType 实体类型（如 "shipping_address", "phone", "contact_name"）
     * @param entityId   实体标识（订单号、用户 ID 等）
     * @param value      新的实体值
     * @return 冲突检测结果
     */
    public ConflictResult update(String sessionId, String entityType, String entityId, String value) {
        if (sessionId == null || entityType == null) return ConflictResult.noConflict(entityType, entityId, value);

        String cacheKey = buildKey(sessionId, entityType, entityId);
        Map<String, EntityState> entities = sessionCache.get(sessionId, k -> new ConcurrentHashMap<>());
        EntityState current = entities.get(cacheKey);

        if (current == null) {
            // 首次记录
            entities.put(cacheKey, new EntityState(value, System.currentTimeMillis()));
            return ConflictResult.noConflict(entityType, entityId, value);
        }

        if (current.value.equals(value)) {
            // 值相同，无冲突
            current.updatedAt = System.currentTimeMillis();
            return ConflictResult.noConflict(entityType, entityId, value);
        }

        // 值不同 → 检测到冲突
        String oldValue = current.value;
        String resolvedValue = resolve(defaultResolution, oldValue, value);
        String description = buildDescription(entityType,entityId,oldValue,value);

        // 更新为新值
        current.value = resolvedValue;
        current.updatedAt = System.currentTimeMillis();

        log.warn("[EntityConflict] 检测到冲突: session={}, type={}, id={}, old={}, new={}, resolved={}",
                truncate(sessionId, 20), entityType, entityId, oldValue, value, resolvedValue);

        return ConflictResult.detected(entityType, entityId, oldValue, value,
                defaultResolution, resolvedValue, description);
    }

    /**
     * 获取实体当前值（不触发冲突检测）。
     */
    public String getValue(String sessionId, String entityType, String entityId) {
        if (sessionId == null) return null;
        Map<String, EntityState> entities = sessionCache.getIfPresent(sessionId);
        if (entities == null) return null;
        EntityState state = entities.get(buildKey(sessionId, entityType, entityId));
        return state != null ? state.value : null;
    }

    /**
     * 获取会话的所有实体状态摘要（用于注入 Agent 提示词）。
     */
    public String getSessionSummary(String sessionId) {
        if (sessionId == null) return "";
        Map<String, EntityState> entities = sessionCache.getIfPresent(sessionId);
        if (entities == null || entities.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("【已知信息】\n");
        for (var entry : entities.entrySet()) {
            String[] parts = entry.getKey().split(":");
            if (parts.length >= 3) {
                sb.append("- ").append(parts[1]).append("(").append(parts[2]).append("): ")
                        .append(entry.getValue().value).append("\n");
            }
        }
        return sb.toString();
    }

    /** 清除会话的实体缓存（会话结束时调用） */
    public void clearSession(String sessionId) {
        sessionCache.invalidate(sessionId);
    }

    /** 获取缓存统计 */
    public String getStats() {
        var stats = sessionCache.stats();
        return String.format("EntityConflict{sessions=%d, hitRate=%.2f}",
                sessionCache.estimatedSize(), stats.hitRate());
    }

    // ==================== 内部方法 ====================

    private String resolve(ConflictResolution resolution, String oldValue, String newValue) {
        return switch (resolution) {
            case NEWER_WINS -> newValue;
            case OLDER_WINS -> oldValue;
            case ASK_USER -> newValue; // 暂用新值，通过 description 提示 Agent 询问用户
            case KEEP_BOTH -> oldValue + " / " + newValue;
            case MERGE -> newValue; // 简化：用新值
        };
    }

    private String buildDescription(String entityType, String entityId, String oldValue, String newValue) {
        if (oldValue.equals(newValue)) return null;
        return String.format("⚠️ 注意：%s(%s)的信息从「%s」变更为「%s」。",
                entityType, entityId, oldValue, newValue);
    }

    private static String buildKey(String sessionId, String entityType, String entityId) {
        return sessionId + ":" + entityType + ":" + (entityId != null ? entityId : "");
    }

    private static String truncate(String str, int max) {
        return str != null && str.length() > max ? str.substring(0, max) + "..." : str;
    }

    /** 内部状态 */
    private static class EntityState {
        String value;
        long updatedAt;
        EntityState(String value, long updatedAt) {
            this.value = value;
            this.updatedAt = updatedAt;
        }
    }
}
