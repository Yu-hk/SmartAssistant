/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.order.tool;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.tool.ToolPageResult;
import com.example.smartassistant.common.tool.ToolResult;
import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import com.example.smartassistant.common.tool.spi.dto.CouponRecommendationDTO;
import com.example.smartassistant.common.tool.spi.dto.UserCouponDTO;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Coupon toolset — provides user coupon queries and optimal combination recommendation.
 * <p>Uses {@link OrderDataProvider} instead of CouponBackend/CouponMapper.</p>
 */
@Component
public class CouponTools {

    private static final Logger log = LoggerFactory.getLogger(CouponTools.class);

    private final OrderDataProvider orderData;
    private final ToolRegistry toolRegistry;

    public CouponTools(OrderDataProvider orderData, ToolRegistry toolRegistry) {
        this.orderData = orderData;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void initTools() {
        toolRegistry.registerAll(java.util.List.of(
                ToolDefinition.read("queryUserCoupons", "查询用户可用优惠券"),
                ToolDefinition.read("findBestCoupon", "计算最优优惠券方案")
        ));
    }

    @Tool(description = "【优惠券】查询用户当前可用的优惠券列表（支持分页），包含满减券、折扣券、现金券等。"
            + "下单前调用此方法，可帮用户找到最省钱的优惠方式。"
            + "如需更多数据，请使用 offset 参数翻页。")
    public String queryUserCoupons(
            @ToolParam(description = "用户ID，如 12345", required = true) Long userId,
            @ToolParam(description = "偏移量，用于翻页。第一页传 0，续读时传上一页返回的 next_offset", required = false) Integer offset,
            @ToolParam(description = "每页条数，默认 10，最大 50", required = false) Integer limit) {
        log.info("[CouponTool] 查询用户优惠券: userId={}, offset={}, limit={}", userId, offset, limit);

        try {
            List<UserCouponDTO> allCoupons = orderData.getUserCoupons(userId);
            if (allCoupons == null || allCoupons.isEmpty()) {
                return ToolResult.success("您目前没有可用的优惠券。");
            }

            int off = (offset != null && offset >= 0) ? offset : 0;
            int lim = (limit != null && limit > 0 && limit <= 50) ? limit : 10;

            List<UserCouponDTO> page = allCoupons.subList(
                    Math.min(off, allCoupons.size()),
                    Math.min(off + lim, allCoupons.size()));

            StringBuilder sb = new StringBuilder("🎫 您有以下可用优惠券：\n\n");
            for (int i = 0; i < page.size(); i++) {
                UserCouponDTO c = page.get(i);
                int idx = off + i + 1;
                String typeLabel = c.getTypeName() != null ? c.getTypeName() : c.getType();
                sb.append(idx).append(". [").append(typeLabel).append("]");
                if (c.getDiscount() != null) {
                    sb.append(" 优惠 ").append(c.getDiscount()).append("元");
                }
                if (c.getMinAmount() != null) {
                    sb.append(" (满").append(c.getMinAmount()).append("可用)");
                }
                sb.append("\n");
            }
            sb.append("\n💡 下单时我可以帮您选择最优惠的券！");

            boolean hasMore = (off + lim) < allCoupons.size();
            return ToolPageResult.builder()
                    .title(null)
                    .items(page)
                    .hasMore(hasMore)
                    .nextOffset(off + lim)
                    .pageSize(lim)
                    .build()
                    .formatWithContinuation(sb.toString(), "queryUserCoupons");
        } catch (Exception e) {
            log.error("[CouponTool] 查询失败: {}", e.getMessage(), e);
            return ToolResult.error(AgentErrorCode.SERVICE_COUPON_QUERY_FAILED, "查询优惠券失败，请稍后重试");
        }
    }

    @Tool(description = "【最优优惠券】根据商品金额，自动计算最省钱的优惠券方案。"
            + "支持满减券、折扣券、现金券的智能对比。"
            + "下单前调用此方法，系统会自动帮用户选出最优优惠券。")
    public String findBestCoupon(
            @ToolParam(description = "用户ID，如 12345") Long userId,
            @ToolParam(description = "商品金额，如 8999.00") BigDecimal amount) {
        log.info("[CouponTool] 寻找最优优惠券: userId={}, amount={}", userId, amount);

        try {
            List<UserCouponDTO> coupons = orderData.getUserCoupons(userId);
            if (coupons == null || coupons.isEmpty()) {
                return ToolResult.success("您目前没有可用的优惠券，直接下单即可。");
            }

            CouponRecommendationDTO best = orderData.findBestCouponCombination(amount, coupons);
            if (best == null) {
                return ToolResult.success("当前没有满足条件的优惠券，直接下单即可。");
            }

            String sb = "💰 最优优惠方案\n" +
                    "━━━━━━━━━━━━━━━━━━\n" +
                    "原价：¥" + best.getOriginalAmount().toPlainString() + "\n" +
                    "优惠：¥" + best.getTotalDiscount().toPlainString() + "\n" +
                    "实付：¥" + best.getFinalAmount().toPlainString() + "\n" +
                    "━━━━━━━━━━━━━━━━━━\n" +
                    "💬 请问您要使用这张优惠券下单吗？";

            return ToolResult.success(sb.trim());
        } catch (Exception e) {
            log.error("[CouponTool] 计算最优方案失败: {}", e.getMessage(), e);
            return ToolResult.error(AgentErrorCode.SERVICE_COUPON_CALC_FAILED, "计算优惠方案失败，请稍后重试");
        }
    }
}
