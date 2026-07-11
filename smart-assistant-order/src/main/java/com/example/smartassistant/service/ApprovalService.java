/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service;

import com.example.smartassistant.entity.ApprovalRecordEntity;
import com.example.smartassistant.entity.ApprovalRecordEntity.ApprovalStatus;
import com.example.smartassistant.entity.ApprovalStateException;
import com.example.smartassistant.mapper.ApprovalRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * 操作审批服务（数据库持久化版 + 充血状态机）
 * <p>
 * 为敏感操作（如退款）提供二阶段确认机制：
 * <ol>
 *   <li>首次调用创建待确认项（写入 approval_records 表）</li>
 *   <li>用户确认后标记为已批准</li>
 *   <li>再次调用时检查并消费确认状态</li>
 * </ol>
 * </p>
 * <p>
 * P0 充血状态机改造要点：
 * <ul>
 *   <li>状态转换逻辑内聚在 {@link ApprovalRecordEntity} 中，
 *       调用 {@code entity.confirm()}/{@code consume()}/{@code cancel()} 校验不变量</li>
 *   <li>DB 层保留 WHERE status= 原子更新作为最终防线</li>
 *   <li>终态（CONSUMED / CANCELLED）不可再转换，重复操作返回 409 语义</li>
 * </ul>
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
     * <p>同一订单同一操作类型已有待确认项时，先取消旧记录再创建新记录。</p>
     *
     * @param orderId    订单号
     * @param actionType 操作类型，如 "refund"
     * @param reason     操作原因
     * @return approvalRecord ID
     */
    @Transactional
    public Long createApproval(String orderId, String actionType, String reason) {
        // 取消之前同订单同类型的待确认记录（调用实体 cancel() 校验状态机）
        ApprovalRecordEntity existing = approvalRecordMapper.findPending(orderId, actionType);
        if (existing != null) {
            try {
                existing.cancel();  // PENDING → CANCELLED（终态）
                approvalRecordMapper.updateById(existing);
                log.info("[ApprovalService] 取消旧待确认操作: orderId={}, actionType={}, id={}",
                        orderId, actionType, existing.getId());
            } catch (ApprovalStateException e) {
                // 旧记录已为非 PENDING（如已被确认），记录日志但不阻塞新记录创建
                log.warn("[ApprovalService] 旧记录状态异常，跳过取消: orderId={}, actionType={}, status={}, reason={}",
                        orderId, actionType, existing.getStatus(), e.getMessage());
            }
        }

        // 创建新的待确认记录（使用实体静态工厂方法）
        ApprovalRecordEntity record = ApprovalRecordEntity.createPending(orderId, actionType, reason);
        approvalRecordMapper.insert(record);
        log.info("[ApprovalService] 创建待确认操作: orderId={}, actionType={}, reason={}, id={}",
                orderId, actionType, reason, record.getId());
        return record.getId();
    }

    /**
     * 确认一个待处理的操作（PENDING → CONFIRMED）。
     *
     * <p>先加载实体并调用 {@link ApprovalRecordEntity#confirm(String, String)} 校验状态机，
     * 再执行 DB 原子更新（WHERE status='pending' 兜底）。</p>
     *
     * @param orderId     订单号
     * @param actionType  操作类型，如 "refund"
     * @param operator    操作人（审计字段）
     * @param operatorIp  操作人 IP（审计字段）
     * @return true 确认成功，false 无待确认项或状态已变化
     */
    @Transactional
    public boolean confirmAction(String orderId, String actionType, String operator, String operatorIp) {
        ApprovalRecordEntity pending = approvalRecordMapper.findPending(orderId, actionType);
        if (pending == null) {
            log.warn("[ApprovalService] 未找到待确认操作: orderId={}, actionType={}", orderId, actionType);
            return false;
        }

        // ⭐ 充血状态机校验：实体层面拦截非法状态转换
        try {
            pending.confirm(operator, operatorIp);
        } catch (ApprovalStateException e) {
            log.warn("[ApprovalService] 确认操作状态异常: orderId={}, actionType={}, reason={}",
                    orderId, actionType, e.getMessage());
            return false;
        }

        // DB 原子更新（WHERE status='pending' 兜底，防止并发下实体校验通过后状态被其他线程修改）
        int updated = approvalRecordMapper.confirmById(pending.getId(), operator, operatorIp);
        if (updated > 0) {
            log.info("[ApprovalService] 操作已确认: orderId={}, actionType={}, id={}, operator={}",
                    orderId, actionType, pending.getId(), operator);
            return true;
        }

        // DB 更新失败（并发场景下其他线程已确认）
        log.warn("[ApprovalService] 确认失败（并发冲突或状态已变化）: id={}, orderId={}, actionType={}",
                pending.getId(), orderId, actionType);
        return false;
    }

    /**
     * 确认一个待处理的操作（无审计字段版本，兼容旧调用）。
     */
    @Transactional
    public boolean confirmAction(String orderId, String actionType) {
        return confirmAction(orderId, actionType, "SYSTEM", "0.0.0.0");
    }

    /**
     * 检查并消费确认状态（CONFIRMED → CONSUMED，调用一次后失效）。
     *
     * <p>先加载实体校验状态机，再执行 DB 原子更新。</p>
     *
     * @param orderId    订单号
     * @param actionType 操作类型
     * @return true 已确认并可执行，false 未确认或已消费
     */
    @Transactional
    public boolean checkAndConsume(String orderId, String actionType) {
        ApprovalRecordEntity confirmed = approvalRecordMapper.findConfirmed(orderId, actionType);
        if (confirmed == null) {
            return false;
        }

        // ⭐ 充血状态机校验
        try {
            confirmed.consume();
        } catch (ApprovalStateException e) {
            log.warn("[ApprovalService] 消费操作状态异常: orderId={}, actionType={}, reason={}",
                    orderId, actionType, e.getMessage());
            return false;
        }

        // DB 原子更新（WHERE status='confirmed' 兜底）
        int updated = approvalRecordMapper.consumeById(confirmed.getId());
        if (updated > 0) {
            log.info("[ApprovalService] 消费确认状态: orderId={}, actionType={}, id={}",
                    orderId, actionType, confirmed.getId());
            return true;
        }

        log.warn("[ApprovalService] 消费失败（并发冲突或状态已变化）: id={}, orderId={}, actionType={}",
                confirmed.getId(), orderId, actionType);
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
        return new PendingApproval(entity.getOrderId(), entity.getActionType(),
                entity.getReason(), ApprovalStatus.CONFIRMED.name().equalsIgnoreCase(entity.getStatus()));
    }

    // ==================== 兼容旧接口的内部类 ====================

    /**
     * 待确认操作信息（兼容对外接口，新代码应直接使用 {@link ApprovalRecordEntity}）。
     */
    public static class PendingApproval {
        private final String orderId;
        private final String actionType;
        private final String reason;
        private final boolean confirmed;

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
    }
}
