/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 审批记录实体类 (MyBatis Plus)
 * 映射 approval_records 表，用于 ApprovalService 的敏感操作二阶段确认持久化
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("approval_records")
public class ApprovalRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("order_id")
    private String orderId;

    @TableField("action_type")
    private String actionType;

    @TableField("reason")
    private String reason;

    /**
     * 状态: pending=待确认, confirmed=已确认待消费, consumed=已消费, cancelled=已取消
     */
    @TableField("status")
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("confirmed_at")
    private LocalDateTime confirmedAt;

    @TableField("consumed_at")
    private LocalDateTime consumedAt;
}
