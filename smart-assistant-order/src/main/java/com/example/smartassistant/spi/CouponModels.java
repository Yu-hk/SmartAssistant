/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.spi;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 优惠券数据模型。
 */
public final class CouponModels {

    private CouponModels() {}

    /** 优惠券类型 */
    public enum CouponType {
        FULL_REDUCTION("满减券"),   // 满200减50
        DISCOUNT("折扣券"),         // 8折
        CASH("现金券");             // 立减20元

        private final String label;
        CouponType(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    /** 用户持有的优惠券 */
    public static class UserCoupon {
        private String id;
        private CouponType type;
        private String title;
        private BigDecimal value;       // 满减:减免金额 / 折扣:折扣率(0.8) / 现金:金额
        private BigDecimal condition;   // 满减门槛，折扣/现金类为 null
        private LocalDateTime expireAt;
        private boolean used;

        public UserCoupon() {}

        public UserCoupon(String id, CouponType type, String title, BigDecimal value, BigDecimal condition, LocalDateTime expireAt) {
            this.id = id;
            this.type = type;
            this.title = title;
            this.value = value;
            this.condition = condition;
            this.expireAt = expireAt;
            this.used = false;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public CouponType getType() { return type; }
        public void setType(CouponType type) { this.type = type; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public BigDecimal getValue() { return value; }
        public void setValue(BigDecimal value) { this.value = value; }
        public BigDecimal getCondition() { return condition; }
        public void setCondition(BigDecimal condition) { this.condition = condition; }
        public LocalDateTime getExpireAt() { return expireAt; }
        public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }
        public boolean isUsed() { return used; }
        public void setUsed(boolean used) { this.used = used; }

        /**
         * 计算该优惠券对指定金额的优惠金额。
         */
        public BigDecimal calculateDiscount(BigDecimal amount) {
            if (used || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO;
            }
            switch (type) {
                case FULL_REDUCTION:
                    // 满 condition 减 value
                    if (condition != null && amount.compareTo(condition) >= 0) {
                        return value != null ? value : BigDecimal.ZERO;
                    }
                    return BigDecimal.ZERO;
                case DISCOUNT:
                    // 打 value 折（value=0.8 表示 8折）
                    if (value != null && value.compareTo(BigDecimal.ZERO) > 0
                            && value.compareTo(BigDecimal.ONE) <= 0) {
                        return amount.multiply(BigDecimal.ONE.subtract(value));
                    }
                    return BigDecimal.ZERO;
                case CASH:
                    // 立减 value 元，不超过商品金额
                    if (value != null && value.compareTo(BigDecimal.ZERO) > 0) {
                        return value.min(amount);
                    }
                    return BigDecimal.ZERO;
                default:
                    return BigDecimal.ZERO;
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(title);
            sb.append("（有效期至").append(expireAt != null ? expireAt.toLocalDate() : "无限").append("）");
            if (used) sb.append(" [已使用]");
            return sb.toString();
        }
    }

    /** 最优优惠券推荐结果 */
    public static class CouponRecommendation {
        private UserCoupon recommended;        // 推荐的优惠券
        private BigDecimal originalAmount;      // 原价
        private BigDecimal discountAmount;      // 优惠金额
        private BigDecimal finalAmount;         // 最终支付金额
        private String reason;                  // 推荐理由

        public CouponRecommendation() {}

        public CouponRecommendation(UserCoupon recommended, BigDecimal originalAmount,
                                     BigDecimal discountAmount, BigDecimal finalAmount, String reason) {
            this.recommended = recommended;
            this.originalAmount = originalAmount;
            this.discountAmount = discountAmount;
            this.finalAmount = finalAmount;
            this.reason = reason;
        }

        public UserCoupon getRecommended() { return recommended; }
        public void setRecommended(UserCoupon recommended) { this.recommended = recommended; }
        public BigDecimal getOriginalAmount() { return originalAmount; }
        public void setOriginalAmount(BigDecimal originalAmount) { this.originalAmount = originalAmount; }
        public BigDecimal getDiscountAmount() { return discountAmount; }
        public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
        public BigDecimal getFinalAmount() { return finalAmount; }
        public void setFinalAmount(BigDecimal finalAmount) { this.finalAmount = finalAmount; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
