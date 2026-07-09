/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.memory;

/**
 * 记忆条目状态。
 *
 * <p>冲突更新时，旧记忆不被物理删除，而是标记为 {@link #SUPERSEDED}（失效版本），
 * 以支持版本留存与回溯审计（对标文章①「版本留存」）。</p>
 */
public enum MemoryStatus {
    /** 当前生效版本 */
    ACTIVE,
    /** 已被新版本覆盖的失效版本（保留用于审计/回溯） */
    SUPERSEDED
}
