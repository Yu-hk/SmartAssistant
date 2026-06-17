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
 * 物流轨迹实体类 (MyBatis Plus)
 * 映射 order_logistics 表，存储快递物流轨迹信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("order_logistics")
public class OrderLogisticsEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("tracking_no")
    private String trackingNo;

    @TableField("order_id")
    private String orderId;

    @TableField("carrier")
    private String carrier;

    /**
     * 状态: pending=待发货, shipped=已发货, in_transit=运输中, delivered=已签收, returned=已退回
     */
    @TableField("status")
    private String status;

    /**
     * 物流轨迹 JSON 数组字符串
     * 格式: [{"time":"...","location":"...","desc":"..."}]
     */
    @TableField("trajectory")
    private String trajectory;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /** 非持久化字段：格式化的轨迹文本 */
    @TableField(exist = false)
    private String trajectoryText;
}
