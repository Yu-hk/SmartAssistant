/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.smartassistant.entity.OrderLogisticsEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 物流轨迹 Mapper (MyBatis Plus)
 */
@Mapper
public interface OrderLogisticsMapper extends BaseMapper<OrderLogisticsEntity> {

    /**
     * 根据快递单号查询物流信息
     */
    @Select("SELECT * FROM order_logistics WHERE tracking_no = #{trackingNo}")
    OrderLogisticsEntity findByTrackingNo(@Param("trackingNo") String trackingNo);

    /**
     * 根据订单号查询物流信息
     */
    @Select("SELECT * FROM order_logistics WHERE order_id = #{orderId} ORDER BY created_at DESC LIMIT 1")
    OrderLogisticsEntity findByOrderId(@Param("orderId") String orderId);
}
