/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.tool.spi;

import com.example.smartassistant.common.tool.spi.dto.CouponRecommendationDTO;
import com.example.smartassistant.common.tool.spi.dto.LogisticsDTO;
import com.example.smartassistant.common.tool.spi.dto.OrderDTO;
import com.example.smartassistant.common.tool.spi.dto.RefundDTO;
import com.example.smartassistant.common.tool.spi.dto.UserCouponDTO;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Order domain data provider — implemented by the order module.
 * <p>Tool code accesses order/logistics/refund/approval/coupon data through this interface.</p>
 */
public interface OrderDataProvider {

    // ========== Order CRUD ==========

    /** Find an order by its business order ID. */
    OrderDTO findOrderByOrderId(String orderId);

    /** Insert a new order. */
    void insertOrder(OrderDTO order);

    /** Update an existing order by its primary key ID. Returns number of rows affected. */
    int updateOrderById(OrderDTO order);

    /** Update payment status and method for an order. */
    void updatePayment(String orderId, String status, String paymentMethod);

    /** Update the status of an order by its business order ID. */
    void updateStatusByOrderId(String orderId, String status);

    // ========== Logistics ==========

    /** Find logistics record by tracking number. */
    LogisticsDTO findLogisticsByTrackingNo(String trackingNo);

    /** Find logistics record by order ID. */
    LogisticsDTO findLogisticsByOrderId(String orderId);

    /** Insert a new logistics record. Returns number of rows affected. */
    int insertLogistics(LogisticsDTO logistics);

    /** Update an existing logistics record by ID. Returns number of rows affected. */
    int updateLogisticsById(LogisticsDTO logistics);

    // ========== Refund ==========

    /** Insert a new refund record. Returns number of rows affected. */
    int insertRefund(RefundDTO refund);

    // ========== Approval ==========

    /** Create an approval record for an action on an order. */
    void createApproval(String orderId, String actionType, String reason);

    /** Confirm a pending approval action. Returns true if confirmed successfully. */
    boolean confirmAction(String orderId, String actionType);

    /** Check and consume a confirmed approval. Returns true if consumable and consumed. */
    boolean checkAndConsume(String orderId, String actionType);

    // ========== Analytics Queries ==========

    /** Query orders filtered by status with pagination. */
    List<Map<String, Object>> queryOrdersByStatus(String status, int limit, int offset);

    /** Count orders grouped by status. */
    List<Map<String, Object>> countOrdersByStatus();

    /** Query top refunds ranked by amount with pagination. */
    List<Map<String, Object>> queryTopRefunds(int limit, int offset);

    /** Query refund records for a specific user. */
    List<Map<String, Object>> queryUserRefunds(Long userId);

    // ========== SQL Query (TextToSqlTool) ==========

    /** Execute a read-only SELECT SQL statement. Returns the result set as a list of maps. */
    List<Map<String, Object>> executeSelectSql(String sql);

    // ========== Coupon ==========

    /** Get available coupons for a user. */
    List<UserCouponDTO> getUserCoupons(Long userId);

    /** Find the best coupon combination for a given amount from a set of coupons. */
    CouponRecommendationDTO findBestCouponCombination(BigDecimal amount, List<UserCouponDTO> coupons);

    // ========== SQL Table Whitelist ==========

    /** Get the list of allowed table names for SQL queries. */
    List<String> getAllowedTables();
}
