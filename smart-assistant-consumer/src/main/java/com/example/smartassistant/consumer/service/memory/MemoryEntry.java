/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.memory;

import lombok.With;

import java.time.LocalDateTime;

/**
 * 版本化记忆条目（对标文章①「长短记忆 - 冲突更新机制 - 版本留存」）。
 *
 * <p>同一条记忆（相同 {@code category + key}）的不同取值构成一条「血缘」(lineageId)，
 * 每次冲突更新产生一个新 {@code version}，旧版本标记为 {@link MemoryStatus#SUPERSEDED}
 * 而非物理删除，从而支持回溯审计。</p>
 *
 * @param id            全局唯一 ID
 * @param lineageId     记忆血缘 ID（同 category+key 的所有版本共享）
 * @param category      记忆类别（如 FOOD_PREF / TRAVEL_PREF / BUDGET）
 * @param key           记忆键（如"辣"、"预算范围"）
 * @param value         记忆值（如"辣"、"经济实惠"）
 * @param source        记忆来源（EXPLICIT / FACT / INFERRED）
 * @param version       版本号（同血缘内从 1 递增）
 * @param status        状态（ACTIVE / SUPERSEDED）
 * @param createdAt     创建时间
 * @param updatedAt     最近更新时间
 * @param supersededAt  被覆盖时间（SUPERSEDED 时有效）
 * @param supersededBy  覆盖本条目者 ID（SUPERSEDED 时有效）
 */
@With
public record MemoryEntry(
        String id,
        String lineageId,
        String category,
        String key,
        String value,
        MemorySource source,
        int version,
        MemoryStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime supersededAt,
        String supersededBy
) {
    /** 记忆键（category + key 组合），用于冲突判定 */
    public String memoryKey() {
        return category + "|" + key;
    }
}
