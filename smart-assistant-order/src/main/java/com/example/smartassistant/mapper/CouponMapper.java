/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.smartassistant.entity.CouponEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 优惠券 Mapper (MyBatis Plus)
 */
@Mapper
public interface CouponMapper extends BaseMapper<CouponEntity> {

    /**
     * 查询用户可用的优惠券（未使用 + 未过期）
     */
    @Select("SELECT * FROM user_coupons WHERE user_id = #{userId} AND used = false AND (expire_at IS NULL OR expire_at > CURRENT_TIMESTAMP)")
    List<CouponEntity> findAvailableByUserId(@Param("userId") Long userId);
}
