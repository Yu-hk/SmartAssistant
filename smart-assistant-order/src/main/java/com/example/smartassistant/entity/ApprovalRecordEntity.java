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
 *
 * <p>P0 充血状态机改造：状态转换逻辑内聚到实体内部，终态不可变。
 * 状态流转：PENDING → CONFIRMED → CONSUMED
 *                  ↘ CANCELLED（仅从 PENDING 可取消）</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
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
     * 状态：PENDING / CONFIRMED / CONSUMED / CANCELLED
     * 终态：CONSUMED、CANCELLED（进入后不可再转换）
     */
    @TableField("status")
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("confirmed_at")
    private LocalDateTime confirmedAt;

    @TableField("consumed_at")
    private LocalDateTime consumedAt;

    @TableField("operator")
    private String operator;

    @TableField("operator_ip")
    private String operatorIp;

    // ==================== 状态机枚举 ====================

    /**
     * 审批状态枚举（充血状态机核心）
     */
    public enum ApprovalStatus {
        PENDING,
        CONFIRMED,
        CONSUMED,
        CANCELLED;

        /** 是否为终态（进入后不可再转换） */
        public boolean isTerminal() {
            return this == CONSUMED || this == CANCELLED;
        }
    }

    // ==================== 状态查询方法 ====================

    /**
     * 获取类型安全的状态枚举
     */
    public ApprovalStatus getStatusEnum() {
        if (status == null) return null;
        try {
            return ApprovalStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 是否处于待确认状态
     */
    public boolean isPending() {
        return ApprovalStatus.PENDING.name().equalsIgnoreCase(status);
    }

    /**
     * 是否处于已确认待消费状态
     */
    public boolean isConfirmed() {
        return ApprovalStatus.CONFIRMED.name().equalsIgnoreCase(status);
    }

    /**
     * 是否为终态（CONSUMED / CANCELLED）
     */
    public boolean isTerminal() {
        ApprovalStatus s = getStatusEnum();
        return s != null && s.isTerminal();
    }

    // ==================== 状态转换方法（充血核心） ====================

    /**
     * 确认操作（PENDING → CONFIRMED）
     * <p>使用 synchronized 保证并发安全：200 线程并发调用时仅 1 次成功。</p>
     *
     * @param operator  操作人（不可为 null 或空）
     * @param operatorIp 操作人 IP（不可为 null 或空）
     * @throws ApprovalStateException 状态转换非法时抛出
     * @throws IllegalArgumentException operator 或 operatorIp 为 null/空时抛出
     */
    public synchronized void confirm(String operator, String operatorIp) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为 null 或空");
        }
        if (operatorIp == null || operatorIp.isBlank()) {
            throw new IllegalArgumentException("operatorIp 不能为 null 或空");
        }
        ApprovalStatus current = getStatusEnum();
        if (current == null) {
            throw new ApprovalStateException(id, status, "CONFIRMED",
                    "记录状态字段非法: status=" + status);
        }
        if (current.isTerminal()) {
            throw new ApprovalStateException(id, status, "CONFIRMED",
                    "记录已为终态(" + status + ")，不可再次确认。订单=" + orderId + "，操作类型=" + actionType);
        }
        if (current != ApprovalStatus.PENDING) {
            throw new ApprovalStateException(id, status, "CONFIRMED",
                    "仅 PENDING 状态可确认，当前状态=" + status);
        }
        this.status = ApprovalStatus.CONFIRMED.name();
        this.confirmedAt = LocalDateTime.now();
        this.operator = operator;
        this.operatorIp = operatorIp;
    }

    /**
     * 消费确认（CONFIRMED → CONSUMED）
     * <p>使用 synchronized 保证并发安全。</p>
     *
     * @throws ApprovalStateException 状态转换非法时抛出
     */
    public synchronized void consume() {
        ApprovalStatus current = getStatusEnum();
        if (current == null) {
            throw new ApprovalStateException(id, status, "CONSUMED",
                    "记录状态字段非法: status=" + status);
        }
        if (current != ApprovalStatus.CONFIRMED) {
            throw new ApprovalStateException(id, status, "CONSUMED",
                    "仅 CONFIRMED 状态可消费，当前状态=" + status + "。订单=" + orderId);
        }
        this.status = ApprovalStatus.CONSUMED.name();
        this.consumedAt = LocalDateTime.now();
    }

    /**
     * 取消操作（PENDING → CANCELLED）
     * <p>使用 synchronized 保证并发安全。</p>
     *
     * @throws ApprovalStateException 状态转换非法时抛出
     */
    public synchronized void cancel() {
        ApprovalStatus current = getStatusEnum();
        if (current == null) {
            throw new ApprovalStateException(id, status, "CANCELLED",
                    "记录状态字段非法: status=" + status);
        }
        if (current != ApprovalStatus.PENDING) {
            throw new ApprovalStateException(id, status, "CANCELLED",
                    "仅 PENDING 状态可取消，当前状态=" + status);
        }
        this.status = ApprovalStatus.CANCELLED.name();
    }

    // ==================== 静态工厂 ====================

    /**
     * 创建待确认记录（替代 Builder，带状态机约束）
     */
    public static ApprovalRecordEntity createPending(String orderId, String actionType, String reason) {
        return ApprovalRecordEntity.builder()
                .orderId(orderId)
                .actionType(actionType)
                .reason(reason)
                .status(ApprovalStatus.PENDING.name())
                .createdAt(LocalDateTime.now())
                .build();
    }
}
