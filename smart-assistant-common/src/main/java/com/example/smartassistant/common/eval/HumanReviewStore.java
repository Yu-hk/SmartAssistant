/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.eval;

import java.util.List;

/**
 * 人工复核任务存储 — 支持存/查待办/裁决。可接 Redis/PG 实现，默认内存。
 *
 * @author Yu-hk
 * @since 2026-07-08
 */
public interface HumanReviewStore {
    void save(HumanReviewTask task);

    List<HumanReviewTask> pending();

    void resolve(String taskId, HumanReviewTask.Status decision, String note);
}
