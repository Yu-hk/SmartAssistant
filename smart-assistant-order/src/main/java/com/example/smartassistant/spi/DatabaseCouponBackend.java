/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.spi;

import com.example.smartassistant.entity.CouponEntity;
import com.example.smartassistant.mapper.CouponMapper;
import com.example.smartassistant.spi.CouponModels.CouponRecommendation;
import com.example.smartassistant.spi.CouponModels.CouponType;
import com.example.smartassistant.spi.CouponModels.UserCoupon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ⭐ 数据库版优惠券后端。
 * <p>
 * 从数据库 {@code user_coupons} 表查询用户优惠券，
 * 计算最优优惠组合。
 * </p>
 */
@Component
public class DatabaseCouponBackend implements CouponBackend {

    private static final Logger log = LoggerFactory.getLogger(DatabaseCouponBackend.class);

    private final CouponMapper couponMapper;

    public DatabaseCouponBackend(CouponMapper couponMapper) {
        this.couponMapper = couponMapper;
    }

    @Override
    public List<UserCoupon> getUserCoupons(Long userId) {
        if (userId == null) return List.of();

        List<CouponEntity> entities = couponMapper.findAvailableByUserId(userId);
        List<UserCoupon> coupons = entities.stream()
                .map(this::toUserCoupon)
                .collect(Collectors.toList());

        log.info("[DBCoupon] 用户 {} 有 {} 张可用优惠券", userId, coupons.size());
        return coupons;
    }

    @Override
    public CouponRecommendation findBestCombination(BigDecimal amount, List<UserCoupon> coupons) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0 || coupons == null || coupons.isEmpty()) {
            return null;
        }

        UserCoupon best = null;
        BigDecimal maxDiscount = BigDecimal.ZERO;

        for (UserCoupon coupon : coupons) {
            if (coupon.isUsed()) continue;
            BigDecimal discount = coupon.calculateDiscount(amount);
            if (discount.compareTo(maxDiscount) > 0) {
                maxDiscount = discount;
                best = coupon;
            }
        }

        if (best == null) {
            log.info("[DBCoupon] 未找到适用优惠券: amount={}", amount);
            return null;
        }

        BigDecimal finalAmount = amount.subtract(maxDiscount).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        maxDiscount = maxDiscount.setScale(2, RoundingMode.HALF_UP);

        String reason = String.format("推荐使用「%s」，可优惠 ¥%s，最终支付 ¥%s",
                best.getTitle(), maxDiscount.toPlainString(), finalAmount.toPlainString());

        log.info("[DBCoupon] 最优推荐: coupon={}, discount={}, final={}",
                best.getId(), maxDiscount, finalAmount);

        return new CouponRecommendation(best, amount.setScale(2, RoundingMode.HALF_UP),
                maxDiscount, finalAmount, reason);
    }

    private UserCoupon toUserCoupon(CouponEntity entity) {
        UserCoupon coupon = new UserCoupon();
        coupon.setId(entity.getCouponId());
        coupon.setType(CouponType.valueOf(entity.getCouponType()));
        coupon.setTitle(entity.getTitle());
        coupon.setValue(entity.getValue());
        coupon.setCondition(entity.getConditionAmount());
        coupon.setExpireAt(entity.getExpireAt());
        coupon.setUsed(entity.getUsed() != null && entity.getUsed());
        return coupon;
    }
}
