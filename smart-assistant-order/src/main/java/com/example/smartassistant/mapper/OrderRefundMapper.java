/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.smartassistant.entity.OrderRefundEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 退款记录 Mapper (MyBatis Plus)
 */
@Mapper
public interface OrderRefundMapper extends BaseMapper<OrderRefundEntity> {

    /**
     * 根据订单号查询退款记录
     */
    @Select("SELECT * FROM order_refunds WHERE order_id = #{orderId} ORDER BY created_at DESC")
    List<OrderRefundEntity> findByOrderId(@Param("orderId") String orderId);
}
