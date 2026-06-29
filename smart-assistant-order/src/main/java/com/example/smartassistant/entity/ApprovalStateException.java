/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.entity;

/**
 * 审批状态机异常——状态转换非法时抛出。
 *
 * <p>场景举例：
 * <ul>
 *   <li>对终态（CONSUMED/CANCELLED）记录尝试确认 → 409 Conflict</li>
 *   <li>对非 PENDING 记录尝试取消</li>
 *   <li>对非 CONFIRMED 记录尝试消费</li>
 * </ul>
 * </p>
 *
 * <p>HTTP 映射建议：status=conflict → 409；status=not_found → 404</p>
 */
public class ApprovalStateException extends RuntimeException {

    private final Long recordId;
    private final String fromStatus;
    private final String toStatus;

    public ApprovalStateException(Long recordId, String fromStatus, String toStatus, String message) {
        super(message);
        this.recordId = recordId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    /** 是否为冲突型错误（终态重复操作） */
    public boolean isConflict() {
        return fromStatus != null && (
                "CONSUMED".equalsIgnoreCase(fromStatus) ||
                "CANCELLED".equalsIgnoreCase(fromStatus));
    }

    /** 是否为资源不存在错误 */
    public boolean isNotFound() {
        return recordId != null && fromStatus == null;
    }

    public Long getRecordId() { return recordId; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
}
