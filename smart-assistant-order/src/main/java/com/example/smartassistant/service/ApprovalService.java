/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 操作审批服务。
 * <p>
 * 为敏感操作（如退款）提供二阶段确认机制：
 * <ol>
 *   <li>首次调用创建待确认项</li>
 *   <li>用户确认后标记为已批准</li>
 *   <li>再次调用时检查并消费确认状态</li>
 * </ol>
 * </p>
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    /** 待确认操作缓存：key = orderId + ":" + actionType, value = 原因描述 */
    private final Map<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();

    /**
     * 创建待确认操作项。
     *
     * @param orderId    订单号
     * @param actionType 操作类型，如 "refund"
     * @param reason     操作原因
     * @return actionKey，用于后续确认
     */
    public String createApproval(String orderId, String actionType, String reason) {
        String key = buildKey(orderId, actionType);
        PendingApproval approval = new PendingApproval(orderId, actionType, reason);
        pendingApprovals.put(key, approval);
        log.info("[ApprovalService] 创建待确认操作: key={}, reason={}", key, reason);
        return key;
    }

    /**
     * 确认一个待处理的操作。
     *
     * @param orderId    订单号
     * @param actionType 操作类型，如 "refund"
     * @return true 确认成功，false 无待确认项
     */
    public boolean confirmAction(String orderId, String actionType) {
        String key = buildKey(orderId, actionType);
        PendingApproval approval = pendingApprovals.get(key);
        if (approval == null) {
            log.warn("[ApprovalService] 未找到待确认操作: key={}", key);
            return false;
        }
        approval.setConfirmed(true);
        log.info("[ApprovalService] 操作已确认: key={}", key);
        return true;
    }

    /**
     * 检查并消费确认状态（调用一次后失效）。
     *
     * @param orderId    订单号
     * @param actionType 操作类型
     * @return true 已确认，false 未确认或已过期
     */
    public boolean checkAndConsume(String orderId, String actionType) {
        String key = buildKey(orderId, actionType);
        PendingApproval approval = pendingApprovals.get(key);
        if (approval == null) {
            return false;
        }
        if (!approval.isConfirmed()) {
            return false;
        }
        // 消费后移除，防止重复使用
        pendingApprovals.remove(key);
        log.info("[ApprovalService] 消费确认状态: key={}", key);
        return true;
    }

    /**
     * 获取待确认操作信息。
     *
     * @param orderId    订单号
     * @param actionType 操作类型
     * @return 待确认操作信息，不存在返回 null
     */
    public PendingApproval getPendingApproval(String orderId, String actionType) {
        return pendingApprovals.get(buildKey(orderId, actionType));
    }

    private static String buildKey(String orderId, String actionType) {
        return orderId + ":" + actionType;
    }

    /**
     * 待确认操作内部数据类。
     */
    public static class PendingApproval {
        private final String orderId;
        private final String actionType;
        private final String reason;
        private volatile boolean confirmed;

        public PendingApproval(String orderId, String actionType, String reason) {
            this.orderId = orderId;
            this.actionType = actionType;
            this.reason = reason;
            this.confirmed = false;
        }

        public String getOrderId() { return orderId; }
        public String getActionType() { return actionType; }
        public String getReason() { return reason; }
        public boolean isConfirmed() { return confirmed; }
        public void setConfirmed(boolean confirmed) { this.confirmed = confirmed; }
    }
}
