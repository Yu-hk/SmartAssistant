/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户优惠券实体类 (MyBatis Plus)
 * 映射 user_coupons 表
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("user_coupons")
public class CouponEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 优惠券编码 */
    private String couponId;

    /** 用户 ID */
    private Long userId;

    /** 优惠券类型：FULL_REDUCTION / DISCOUNT / CASH */
    private String couponType;

    /** 优惠券标题 */
    private String title;

    /** 优惠值：满减=减免金额，折扣=折扣率(0.8)，现金=金额 */
    private BigDecimal value;

    /** 使用条件（满减券的门槛金额），折扣/现金券为 NULL */
    private BigDecimal conditionAmount;

    /** 是否已使用 */
    private Boolean used;

    /** 过期时间 */
    private LocalDateTime expireAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
