/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.smartassistant.entity.OrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 订单 Mapper (MyBatis Plus)
 */
@Mapper
public interface OrderMapper extends BaseMapper<OrderEntity> {

    /**
     * 根据订单号查询订单
     */
    @Select("SELECT * FROM orders WHERE order_id = #{orderId}")
    OrderEntity findByOrderId(@Param("orderId") String orderId);

    /**
     * 根据用户ID查询所有订单
     */
    @Select("SELECT * FROM orders WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<OrderEntity> findByUserId(@Param("userId") Long userId);

    /**
     * 查询所有订单
     */
    @Select("SELECT * FROM orders ORDER BY created_at DESC")
    List<OrderEntity> findAllOrders();

    /**
     * 更新订单状态（按 order_id），同步更新 updated_at
     */
    @Update("UPDATE orders SET status = #{status}, updated_at = CURRENT_TIMESTAMP WHERE order_id = #{orderId}")
    int updateStatusByOrderId(@Param("orderId") String orderId, @Param("status") String status);

    /**
     * 更新订单物流信息，同步更新 updated_at
     */
    @Update("UPDATE orders SET carrier = #{carrier}, tracking_no = #{trackingNo}, status = #{status}, updated_at = CURRENT_TIMESTAMP WHERE order_id = #{orderId}")
    int updateLogistics(@Param("orderId") String orderId,
                        @Param("carrier") String carrier,
                        @Param("trackingNo") String trackingNo,
                        @Param("status") String status);

    /**
     * 更新订单签收信息，同步更新 updated_at
     */
    @Update("UPDATE orders SET status = #{status}, delivered_date = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE order_id = #{orderId}")
    int updateDelivered(@Param("orderId") String orderId, @Param("status") String status);

    /**
     * 更新订单支付信息，同步更新 updated_at
     */
    @Update("UPDATE orders SET status = #{status}, payment_method = #{paymentMethod}, updated_at = CURRENT_TIMESTAMP WHERE order_id = #{orderId}")
    int updatePayment(@Param("orderId") String orderId,
                      @Param("status") String status,
                      @Param("paymentMethod") String paymentMethod);

    /**
     * 查询用户最近的订单
     */
    @Select("SELECT * FROM orders WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT #{limit}")
    List<OrderEntity> findRecentByUserId(@Param("userId") Long userId, @Param("limit") int limit);
}
