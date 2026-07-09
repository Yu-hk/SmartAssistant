/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.memory;

/**
 * 记忆来源（对标文章①「冲突更新优先级」）。
 *
 * <p>优先级（数值越大越优先，冲突时高优先级覆盖低优先级）：</p>
 * <ul>
 *     <li>{@link #EXPLICIT} 用户明确声明（如"我只吃素食"）→ 最高</li>
 *     <li>{@link #FACT}    客观事实（如用户所在城市、订单状态）→ 次高</li>
 *     <li>{@link #INFERRED} 模型/规则推断（如从对话推测的偏好）→ 最低</li>
 * </ul>
 */
public enum MemorySource {
    INFERRED(1),
    FACT(2),
    EXPLICIT(3);

    private final int rank;

    MemorySource(int rank) { this.rank = rank; }

    /** 优先级数值，越大越优先 */
    public int rank() { return rank; }
}
