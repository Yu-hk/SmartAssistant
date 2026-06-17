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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款记录实体类 (MyBatis Plus)
 * 映射 order_refunds 表，记录每一笔退款申请的详细信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("order_refunds")
public class OrderRefundEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("order_id")
    private String orderId;

    @TableField("reason")
    private String reason;

    @TableField("amount")
    private BigDecimal amount;

    /**
     * 状态: pending=待审核, approved=已批准, rejected=已拒绝, completed=已完成(已打款)
     */
    @TableField("status")
    private String status;

    @TableField("created_by")
    private String createdBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
