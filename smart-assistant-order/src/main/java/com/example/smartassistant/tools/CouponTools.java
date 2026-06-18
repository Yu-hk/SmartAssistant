/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.tools;

import com.example.smartassistant.common.tool.ToolResult;
import com.example.smartassistant.spi.CouponBackend;
import com.example.smartassistant.spi.CouponModels.CouponRecommendation;
import com.example.smartassistant.spi.CouponModels.UserCoupon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 优惠券工具集。
 * <p>
 * 提供用户优惠券查询和最优组合推荐功能，在下单流程中使用。
 * </p>
 */
@Component
public class CouponTools {

    private static final Logger log = LoggerFactory.getLogger(CouponTools.class);

    private final CouponBackend couponBackend;

    public CouponTools(CouponBackend couponBackend) {
        this.couponBackend = couponBackend;
    }

    /**
     * 查询用户可用的优惠券列表。
     */
    @Tool(description = "【优惠券】查询用户当前可用的优惠券列表，包含满减券、折扣券、现金券等。"
            + "下单前调用此方法，可帮用户找到最省钱的优惠方式。")
    public String queryUserCoupons(
            @ToolParam(description = "用户ID", required = true) Long userId) {
        log.info("[CouponTool] 查询用户优惠券: userId={}", userId);

        try {
            List<UserCoupon> coupons = couponBackend.getUserCoupons(userId);
            if (coupons == null || coupons.isEmpty()) {
                return ToolResult.success("您目前没有可用的优惠券。");
            }

            StringBuilder sb = new StringBuilder("🎫 您有以下可用优惠券：\n\n");
            for (int i = 0; i < coupons.size(); i++) {
                UserCoupon c = coupons.get(i);
                sb.append(i + 1).append(". ").append(c.getTitle()).append("\n");
                sb.append("   ").append(c).append("\n\n");
            }
            sb.append("💡 下单时我可以帮您选择最优惠的券！");
            return ToolResult.success(sb.toString().trim());
        } catch (Exception e) {
            log.error("[CouponTool] 查询失败: {}", e.getMessage(), e);
            return ToolResult.error("COUPON_QUERY_FAILED", "查询优惠券失败，请稍后重试", true);
        }
    }

    /**
     * 根据商品金额找出最优优惠券组合。
     */
    @Tool(description = "【最优优惠券】根据商品金额，自动计算最省钱的优惠券方案。"
            + "支持满减券、折扣券、现金券的智能对比。"
            + "下单前调用此方法，系统会自动帮用户选出最优优惠券。")
    public String findBestCoupon(
            @ToolParam(description = "用户ID", required = true) Long userId,
            @ToolParam(description = "商品金额，如 8999.00", required = true) BigDecimal amount) {
        log.info("[CouponTool] 寻找最优优惠券: userId={}, amount={}", userId, amount);

        try {
            List<UserCoupon> coupons = couponBackend.getUserCoupons(userId);
            if (coupons == null || coupons.isEmpty()) {
                return ToolResult.success("您目前没有可用的优惠券，直接下单即可。");
            }

            CouponRecommendation best = couponBackend.findBestCombination(amount, coupons);
            if (best == null) {
                return ToolResult.success("当前没有满足条件的优惠券，直接下单即可。");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("💰 最优优惠方案\n");
            sb.append("━━━━━━━━━━━━━━━━━━\n");
            sb.append("原价：¥").append(best.getOriginalAmount().toPlainString()).append("\n");
            sb.append("优惠：¥").append(best.getDiscountAmount().toPlainString()).append("\n");
            sb.append("实付：¥").append(best.getFinalAmount().toPlainString()).append("\n");
            sb.append("━━━━━━━━━━━━━━━━━━\n");
            sb.append(best.getReason()).append("\n\n");
            sb.append("💬 请问您要使用这张优惠券下单吗？");

            return ToolResult.success(sb.toString().trim());
        } catch (Exception e) {
            log.error("[CouponTool] 计算最优方案失败: {}", e.getMessage(), e);
            return ToolResult.error("COUPON_CALC_FAILED", "计算优惠方案失败，请稍后重试", true);
        }
    }
}
