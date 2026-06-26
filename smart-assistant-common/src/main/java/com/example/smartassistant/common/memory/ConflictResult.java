/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.memory;

/**
 * 实体冲突检测结果。
 */
public class ConflictResult {

    /** 是否检测到冲突 */
    private final boolean conflict;

    /** 实体类型 */
    private final String entityType;

    /** 实体标识 */
    private final String entityId;

    /** 冲突前保存的值 */
    private final String oldValue;

    /** 本次传入的新值 */
    private final String newValue;

    /** 采用的解决策略 */
    private final ConflictResolution resolution;

    /** 解决后的最终值 */
    private final String resolvedValue;

    /** 冲突描述（用于注入 Agent 提示词） */
    private final String description;

    public ConflictResult(boolean conflict, String entityType, String entityId,
                          String oldValue, String newValue,
                          ConflictResolution resolution, String resolvedValue,
                          String description) {
        this.conflict = conflict;
        this.entityType = entityType;
        this.entityId = entityId;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.resolution = resolution;
        this.resolvedValue = resolvedValue;
        this.description = description;
    }

    /** 快速构造"无冲突"结果 */
    public static ConflictResult noConflict(String entityType, String entityId, String value) {
        return new ConflictResult(false, entityType, entityId, null, value,
                ConflictResolution.NEWER_WINS, value, null);
    }

    /** 快速构造"冲突"结果 */
    public static ConflictResult detected(String entityType, String entityId,
                                          String oldValue, String newValue,
                                          ConflictResolution resolution,
                                          String resolvedValue, String description) {
        return new ConflictResult(true, entityType, entityId, oldValue, newValue,
                resolution, resolvedValue, description);
    }

    public boolean isConflict() { return conflict; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public ConflictResolution getResolution() { return resolution; }
    public String getResolvedValue() { return resolvedValue; }
    public String getDescription() { return description; }
}
