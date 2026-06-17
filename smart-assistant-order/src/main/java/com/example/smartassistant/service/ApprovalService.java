/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service;

import com.example.smartassistant.entity.ApprovalRecordEntity;
import com.example.smartassistant.mapper.ApprovalRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 操作审批服务（数据库持久化版）
 * <p>
 * 为敏感操作（如退款）提供二阶段确认机制：
 * <ol>
 *   <li>首次调用创建待确认项（写入 approval_records 表）</li>
 *   <li>用户确认后标记为已批准</li>
 *   <li>再次调用时检查并消费确认状态</li>
 * </ol>
 * </p>
 * <p>
 * ⭐ 重构说明：从 ConcurrentHashMap 内存存储迁移到 PostgreSQL 数据库持久化，
 * 服务重启后数据不丢失。
 * </p>
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ApprovalRecordMapper approvalRecordMapper;

    public ApprovalService(ApprovalRecordMapper approvalRecordMapper) {
        this.approvalRecordMapper = approvalRecordMapper;
    }

    /**
     * 创建待确认操作项（数据库持久化）。
     *
     * @param orderId    订单号
     * @param actionType 操作类型，如 "refund"
     * @param reason     操作原因
     * @return approvalRecord ID
     */
    @Transactional
    public Long createApproval(String orderId, String actionType, String reason) {
        // 先取消之前同订单同类型的待确认记录
        ApprovalRecordEntity existing = approvalRecordMapper.findPending(orderId, actionType);
        if (existing != null) {
            existing.setStatus("cancelled");
            approvalRecordMapper.updateById(existing);
            log.info("[ApprovalService] 取消旧待确认操作: key={}:{}, id={}", orderId, actionType, existing.getId());
        }

        // 创建新的待确认记录
        ApprovalRecordEntity record = ApprovalRecordEntity.builder()
                .orderId(orderId)
                .actionType(actionType)
                .reason(reason)
                .status("pending")
                .createdAt(LocalDateTime.now())
                .build();

        approvalRecordMapper.insert(record);
        log.info("[ApprovalService] 创建待确认操作: orderId={}, actionType={}, reason={}, id={}",
                orderId, actionType, reason, record.getId());
        return record.getId();
    }

    /**
     * 确认一个待处理的操作。
     *
     * @param orderId    订单号
     * @param actionType 操作类型，如 "refund"
     * @return true 确认成功，false 无待确认项
     */
    @Transactional
    public boolean confirmAction(String orderId, String actionType) {
        ApprovalRecordEntity pending = approvalRecordMapper.findPending(orderId, actionType);
        if (pending == null) {
            log.warn("[ApprovalService] 未找到待确认操作: orderId={}, actionType={}", orderId, actionType);
            return false;
        }

        int updated = approvalRecordMapper.confirmById(pending.getId());
        if (updated > 0) {
            log.info("[ApprovalService] 操作已确认: orderId={}, actionType={}, id={}", orderId, actionType, pending.getId());
            return true;
        }

        log.warn("[ApprovalService] 确认失败（并发冲突）: id={}", pending.getId());
        return false;
    }

    /**
     * 检查并消费确认状态（调用一次后失效）。
     *
     * @param orderId    订单号
     * @param actionType 操作类型
     * @return true 已确认，false 未确认或已过期
     */
    @Transactional
    public boolean checkAndConsume(String orderId, String actionType) {
        ApprovalRecordEntity confirmed = approvalRecordMapper.findConfirmed(orderId, actionType);
        if (confirmed == null) {
            return false;
        }

        int updated = approvalRecordMapper.consumeById(confirmed.getId());
        if (updated > 0) {
            log.info("[ApprovalService] 消费确认状态: orderId={}, actionType={}, id={}", orderId, actionType, confirmed.getId());
            return true;
        }

        log.warn("[ApprovalService] 消费失败（可能已被其他线程消费）: id={}", confirmed.getId());
        return false;
    }

    /**
     * 获取待确认操作信息。
     *
     * @param orderId    订单号
     * @param actionType 操作类型
     * @return 待确认操作信息，不存在返回 null
     */
    public PendingApproval getPendingApproval(String orderId, String actionType) {
        ApprovalRecordEntity entity = approvalRecordMapper.findPending(orderId, actionType);
        if (entity == null) {
            return null;
        }
        return new PendingApproval(entity.getOrderId(), entity.getActionType(), entity.getReason(), false);
    }

    /**
     * 待确认操作内部数据类（兼容对外接口）。
     */
    public static class PendingApproval {
        private final String orderId;
        private final String actionType;
        private final String reason;
        private volatile boolean confirmed;

        public PendingApproval(String orderId, String actionType, String reason, boolean confirmed) {
            this.orderId = orderId;
            this.actionType = actionType;
            this.reason = reason;
            this.confirmed = confirmed;
        }

        public String getOrderId() { return orderId; }
        public String getActionType() { return actionType; }
        public String getReason() { return reason; }
        public boolean isConfirmed() { return confirmed; }
        public void setConfirmed(boolean confirmed) { this.confirmed = confirmed; }
    }
}
