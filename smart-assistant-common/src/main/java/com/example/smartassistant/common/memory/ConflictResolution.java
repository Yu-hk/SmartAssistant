/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.memory;

/**
 * 冲突解决策略——决定当实体信息发生冲突时如何解决。
 */
public enum ConflictResolution {

    /** 新值覆盖旧值（默认策略：时间优先） */
    NEWER_WINS,

    /** 旧值保持不变（信任首次提供的信息） */
    OLDER_WINS,

    /** 要求用户确认哪个值正确 */
    ASK_USER,

    /** 保持两个值，标记为不确定 */
    KEEP_BOTH,

    /** 自动合并（如地址补充而非矛盾） */
    MERGE
}
