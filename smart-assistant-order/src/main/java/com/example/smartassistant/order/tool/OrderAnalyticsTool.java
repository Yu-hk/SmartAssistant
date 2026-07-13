/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.order.tool;

import com.example.smartassistant.common.tool.ToolPageResult;
import com.example.smartassistant.common.tool.spi.OrderDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Order analytics tool — predefined parameterized queries via {@link OrderDataProvider}.
 * <p>Replaces direct JdbcTemplate usage with SPI calls.</p>
 */
@Component
public class OrderAnalyticsTool {

    private static final Logger log = LoggerFactory.getLogger(OrderAnalyticsTool.class);

    private final OrderDataProvider orderData;

    public OrderAnalyticsTool(OrderDataProvider orderData) {
        this.orderData = orderData;
    }


    @Tool(description = "查询指定状态的订单列表（支持分页）。"
            + "适用于：'有哪些待发货的订单'、'已退款的有哪些'等统计类问题。"
            + "不适用于：查询单个订单的详细状态（请用 queryOrder）。")
    public String queryOrdersByStatus(
            @ToolParam(description = "订单状态：待付款 / 待发货 / 已发货 / 已签收 / 已取消 / 退款中", required = true)
            String status,
            @ToolParam(description = "返回条数上限，默认 10，最大 50", required = false) Integer limit,
            @ToolParam(description = "偏移量，用于翻页。第一页传 0，续读时传上一页返回的 next_offset", required = false) Integer offset) {

        int n = (limit != null && limit > 0 && limit <= 50) ? limit : 10;
        int off = (offset != null && offset >= 0) ? offset : 0;
        log.info("[Analytics] queryOrdersByStatus: status={}, limit={}, offset={}", status, n, off);

        List<Map<String, Object>> rows = orderData.queryOrdersByStatus(status, n, off);

        if (rows.isEmpty()) {
            return off == 0 ? "📊 没有找到状态为「" + status + "」的订单。"
                    : "📊 没有更多数据了。已显示全部 " + off + " 条订单。";
        }

        String table = formatTable("「" + status + "」订单 (第 " + (off + 1) + "~" + (off + rows.size()) + " 条)", rows);
        boolean hasMore = rows.size() >= n;
        return ToolPageResult.builder()
                .title(null)
                .items(rows)
                .hasMore(hasMore)
                .nextOffset(off + n)
                .pageSize(n)
                .build()
                .formatWithContinuation(table, "queryOrdersByStatus");
    }

    @Tool(description = "统计各状态的订单数量。"
            + "适用于：'订单总体情况怎么样'、'各状态订单数'等汇总问题。"
            + "不适用于：查具体某笔订单（请用 queryOrder）。")
    public String countOrdersByStatus() {
        log.info("[Analytics] countOrdersByStatus");

        List<Map<String, Object>> rows = orderData.countOrdersByStatus();

        if (rows.isEmpty()) return "📊 暂无订单数据。";
        return formatTable("订单状态汇总", rows);
    }

    @Tool(description = "查询退款金额最高的订单排行（支持分页）。"
            + "适用于：'退款最多的是哪些'、'高额退款排行'等分析问题。"
            + "不适用于：查询某个用户的退款记录（请用 queryUserRefunds）。")
    public String queryTopRefunds(
            @ToolParam(description = "返回条数，默认 5，最大 20", required = false) Integer limit,
            @ToolParam(description = "偏移量，用于翻页。第一页传 0", required = false) Integer offset) {

        int n = (limit != null && limit > 0 && limit <= 20) ? limit : 5;
        int off = (offset != null && offset >= 0) ? offset : 0;
        log.info("[Analytics] queryTopRefunds: limit={}, offset={}", n, off);

        List<Map<String, Object>> rows = orderData.queryTopRefunds(n, off);

        if (rows.isEmpty()) {
            return off == 0 ? "📊 暂无退款记录。" : "📊 没有更多退款记录了。";
        }
        String table = formatTable("退款金额排行 (第 " + (off + 1) + "~" + (off + rows.size()) + " 条)", rows);
        boolean hasMore = rows.size() >= n;
        return ToolPageResult.builder()
                .title(null)
                .items(rows)
                .hasMore(hasMore)
                .nextOffset(off + n)
                .pageSize(n)
                .build()
                .formatWithContinuation(table, "queryTopRefunds");
    }

    @Tool(description = "查询某用户的所有退款记录。"
            + "适用于：'查一下XX用户的退款情况'、'这个用户退过多少次'等。"
            + "不适用于：统计整体退款趋势（请用 queryTopRefunds）。")
    public String queryUserRefunds(
            @ToolParam(description = "用户ID，如 12345") Long userId) {

        log.info("[Analytics] queryUserRefunds: userId={}", userId);

        List<Map<String, Object>> rows = orderData.queryUserRefunds(userId);

        if (rows.isEmpty()) return "📊 用户 " + userId + " 暂无退款记录。";
        return formatTable("用户 " + userId + " 的退款记录 (共 " + rows.size() + " 条)", rows);
    }

    private String formatTable(String title, List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 ").append(title).append("\n\n");

        if (rows.isEmpty()) { sb.append("(无数据)\n"); return sb.toString(); }

        List<String> keys = new ArrayList<>(rows.getFirst().keySet());
        sb.append("| ").append(String.join(" | ", keys)).append(" |\n");
        sb.append("|").append("---|".repeat(keys.size())).append("\n");

        for (Map<String, Object> row : rows) {
            for (String k : keys) {
                Object v = row.get(k);
                String val = v != null ? v.toString() : "-";
                sb.append("| ").append(val.length() > 30 ? val.substring(0, 28) + ".." : val).append(" ");
            }
            sb.append("|\n");
        }

        return sb.toString();
    }
}
