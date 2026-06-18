/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.spi;

import com.example.smartassistant.spi.CouponModels.CouponRecommendation;
import com.example.smartassistant.spi.CouponModels.UserCoupon;

import java.math.BigDecimal;
import java.util.List;

/**
 * ⭐ 优惠券数据后端 SPI。
 * <p>
 * 下游集成商可以通过实现此接口替换默认的优惠券数据源。
 * 框架提供 {@link InMemoryCouponBackend} 作为默认 Mock 实现，
 * 通过 {@code @ConditionalOnMissingBean} 自动注册。
 * </p>
 *
 * <h3>接入方式</h3>
 * <pre>
 * &#64;Component
 * public class MyDbCouponBackend implements CouponBackend {
 *     // Spring 自动检测到此 Bean，默认的 InMemoryCouponBackend 自动让位
 * }
 * </pre>
 */
public interface CouponBackend {

    /**
     * 查询用户可用的优惠券列表。
     * @param userId 用户 ID
     * @return 可用优惠券列表（已过滤掉已使用和已过期的）
     */
    List<UserCoupon> getUserCoupons(Long userId);

    /**
     * 根据商品金额找出最优优惠券组合。
     * <p>
     * 从用户可用优惠券中，选取优惠金额最大的一张。
     * </p>
     *
     * @param amount  商品金额
     * @param coupons 用户可用优惠券列表
     * @return 最优优惠券推荐结果（可能为 null，无合适优惠券时）
     */
    CouponRecommendation findBestCombination(BigDecimal amount, List<UserCoupon> coupons);
}
