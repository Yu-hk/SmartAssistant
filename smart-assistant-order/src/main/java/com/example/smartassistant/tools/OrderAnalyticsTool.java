/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 订单分析工具 — 预定义的参数化查询，替代 TextToSqlTool。
 * <p>
 * 不再让 LLM 直接生成 SQL（这是反模式），改为提供明确参数、后端拼接 SQL 的方式。
 * 所有查询均为只读 SELECT，权限在后端控制。
 */
@Component
public class OrderAnalyticsTool {

    private static final Logger log = LoggerFactory.getLogger(OrderAnalyticsTool.class);

    private final JdbcTemplate jdbcTemplate;

    public OrderAnalyticsTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(description = "查询指定状态的订单列表。"
            + "适用于：'有哪些待发货的订单'、'已退款的有哪些'等统计类问题。"
            + "不适用于：查询单个订单的详细状态（请用 queryOrder）。")
    public String queryOrdersByStatus(
            @ToolParam(description = "订单状态：待付款 / 待发货 / 已发货 / 已签收 / 已取消 / 退款中", required = true)
            String status,
            @ToolParam(description = "返回条数上限，默认 10", required = false) Integer limit) {

        int n = (limit != null && limit > 0 && limit <= 50) ? limit : 10;
        log.info("[Analytics] queryOrdersByStatus: status={}, limit={}", status, n);

        String sql = "SELECT order_id, user_id, product_name, amount, status, created_at "
                + "FROM orders WHERE status = ? ORDER BY created_at DESC LIMIT ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, status, n);

        if (rows.isEmpty()) return "📊 没有找到状态为「" + status + "」的订单。";
        return formatTable("「" + status + "」订单 (共 " + rows.size() + " 条)", rows);
    }

    @Tool(description = "统计各状态的订单数量。"
            + "适用于：'订单总体情况怎么样'、'各状态订单数'等汇总问题。"
            + "不适用于：查具体某笔订单（请用 queryOrder）。")
    public String countOrdersByStatus() {
        log.info("[Analytics] countOrdersByStatus");

        String sql = "SELECT status AS \"状态\", COUNT(*) AS \"数量\" "
                + "FROM orders GROUP BY status ORDER BY COUNT(*) DESC";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

        if (rows.isEmpty()) return "📊 暂无订单数据。";
        return formatTable("订单状态汇总", rows);
    }

    @Tool(description = "查询退款金额最高的订单排行。"
            + "适用于：'退款最多的是哪些'、'高额退款排行'等分析问题。"
            + "不适用于：查询某个用户的退款记录（请用 queryUserRefunds）。")
    public String queryTopRefunds(
            @ToolParam(description = "返回条数，默认 5，最大 20", required = false) Integer limit) {

        int n = (limit != null && limit > 0 && limit <= 20) ? limit : 5;
        log.info("[Analytics] queryTopRefunds: limit={}", n);

        String sql = "SELECT r.order_id AS \"订单号\", r.reason AS \"退款原因\", "
                + "r.amount AS \"退款金额\", r.status AS \"退款状态\", "
                + "o.product_name AS \"商品\" "
                + "FROM order_refunds r LEFT JOIN orders o ON r.order_id = o.order_id "
                + "ORDER BY r.amount DESC LIMIT ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, n);

        if (rows.isEmpty()) return "📊 暂无退款记录。";
        return formatTable("退款金额 TOP " + n, rows);
    }

    @Tool(description = "查询某用户的所有退款记录。"
            + "适用于：'查一下XX用户的退款情况'、'这个用户退过多少次'等。"
            + "不适用于：统计整体退款趋势（请用 queryTopRefunds）。")
    public String queryUserRefunds(
            @ToolParam(description = "用户ID", required = true) Long userId) {

        log.info("[Analytics] queryUserRefunds: userId={}", userId);

        String sql = "SELECT o.order_id AS \"订单号\", o.product_name AS \"商品\", "
                + "r.reason AS \"退款原因\", r.amount AS \"退款金额\", "
                + "r.status AS \"退款状态\", r.created_at AS \"申请时间\" "
                + "FROM order_refunds r JOIN orders o ON r.order_id = o.order_id "
                + "WHERE o.user_id = ? ORDER BY r.created_at DESC";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, userId);

        if (rows.isEmpty()) return "📊 用户 " + userId + " 暂无退款记录。";
        return formatTable("用户 " + userId + " 的退款记录 (共 " + rows.size() + " 条)", rows);
    }

    /**
     * 简单 Markdown 表格格式化。
     */
    private String formatTable(String title, List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 ").append(title).append("\n\n");

        if (rows.isEmpty()) { sb.append("(无数据)\n"); return sb.toString(); }

        // Header
        List<String> keys = new ArrayList<>(rows.get(0).keySet());
        sb.append("| ").append(String.join(" | ", keys)).append(" |\n");
        sb.append("|").append("---|".repeat(keys.size())).append("\n");

        // Rows
        for (Map<String, Object> row : rows) {
            for (String k : keys) {
                Object v = row.get(k);
                String val = v != null ? v.toString() : "-";
                sb.append("| ").append(val.length() > 30 ? val.substring(0,28)+".." : val).append(" ");
            }
            sb.append("|\n");
        }

        return sb.toString();
    }
}
