/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.admin;

import com.example.smartassistant.common.db.DatabaseDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 管理后台服务。
 *
 * <p>提供统计、会话管理、FAQ 管理功能。
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseDialect dialect;

    /** FAQ 内存存储 */
    private final ConcurrentHashMap<String, FaqItem> faqStore = new ConcurrentHashMap<>();
    private final AtomicLong faqIdSeq = new AtomicLong(1);

    public AdminService(JdbcTemplate jdbcTemplate, DatabaseDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
        initDefaultFaqs();
    }

    // ==================== 统计数据 ====================

    /**
     * 获取管理后台总览统计（AdminStats 格式）。
     */
    public AdminStats getStats() {
        try {
            long totalConversations = queryLong("SELECT COUNT(*) FROM conversation_feedback");
            long totalUsers = queryLong("SELECT COUNT(DISTINCT user_id) FROM conversation_feedback");
            long ratedCount = queryLong("SELECT COUNT(*) FROM conversation_feedback WHERE rating IS NOT NULL");
            double avgScore = queryDouble(
                    "SELECT COALESCE(AVG(rating), 0) FROM conversation_feedback WHERE rating IS NOT NULL");

            // 评分分布
            SatisfactionStats satisfaction = new SatisfactionStats(
                    totalConversations, ratedCount,
                    ratedCount > 0 ? avgScore : null,
                    queryLong("SELECT COUNT(*) FROM conversation_feedback WHERE rating = 1"),
                    queryLong("SELECT COUNT(*) FROM conversation_feedback WHERE rating = 2"),
                    queryLong("SELECT COUNT(*) FROM conversation_feedback WHERE rating = 3"),
                    queryLong("SELECT COUNT(*) FROM conversation_feedback WHERE rating = 4"),
                    queryLong("SELECT COUNT(*) FROM conversation_feedback WHERE rating = 5")
            );

            // 意图分布
            List<Map<String, Object>> intentRows = jdbcTemplate.queryForList(
                    "SELECT routed_agent, COUNT(*) as cnt FROM routing_call_log " +
                    "WHERE created_at >= NOW() - INTERVAL '30 days' " +
                    "GROUP BY routed_agent ORDER BY cnt DESC");
            List<IntentStats> intents = intentRows.stream().map(row ->
                    new IntentStats(
                            mapAgentToIntent((String) row.get("routed_agent")),
                            ((Number) row.get("cnt")).longValue(),
                            0L
                    )
            ).collect(Collectors.toList());

            // 每日趋势（近7天）
            List<Map<String, Object>> dailyRows = jdbcTemplate.queryForList(
                    "SELECT DATE(created_at) as day, COUNT(*) as cnt " +
                    "FROM routing_call_log " +
                    "WHERE created_at >= NOW() - INTERVAL '7 days' " +
                    "GROUP BY DATE(created_at) ORDER BY day");
            List<DailyStats> daily = dailyRows.stream().map(row -> {
                LocalDate d = row.get("day") instanceof java.sql.Date
                        ? ((java.sql.Date) row.get("day")).toLocalDate()
                        : LocalDate.now();
                return new DailyStats(d.toString(), ((Number) row.get("cnt")).longValue(), null);
            }).collect(Collectors.toList());

            // 转人工率
            long transferCount = queryLong(
                    "SELECT COUNT(*) FROM routing_call_log WHERE routed_agent = 'human'");
            double transferRate = totalConversations > 0
                    ? Math.round(1000.0 * transferCount / totalConversations) / 10.0 : 0;

            log.info("[Admin] 统计数据: conversations={}, users={}, avgScore={}, transferRate={}%",
                    totalConversations, totalUsers, avgScore, transferRate);

            return new AdminStats(satisfaction, intents, daily, transferRate);

        } catch (Exception e) {
            log.warn("[Admin] 统计数据查询失败: {}", e.getMessage());
            return AdminStats.empty();
        }
    }

    // ==================== 会话管理 ====================

    public List<Map<String, Object>> getSessions() {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT r.session_id as id, " +
                    "LEFT(r.user_input, 100) as title, " +
                    "r.routed_agent as intent, " +
                    "r.created_at::text as created_at, " +
                    "r.status, " +
                    "COALESCE(f.rating, 0) as satisfaction, " +
                    "COALESCE(f.feedback_text, '') as satisfaction_comment " +
                    "FROM routing_call_log r " +
                    "LEFT JOIN conversation_feedback f ON r.session_id = f.session_id " +
                    "WHERE r.session_id IS NOT NULL " +
                    "GROUP BY r.session_id, r.user_input, r.routed_agent, r.created_at, r.status, f.rating, f.feedback_text " +
                    "ORDER BY r.created_at DESC LIMIT 200");
        } catch (Exception e) {
            log.warn("[Admin] 会话列表查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    public Map<String, Object> getSessionDetail(String sessionId) {
        try {
            List<Map<String, Object>> logs = jdbcTemplate.queryForList(
                    "SELECT * FROM routing_call_log WHERE session_id = ? ORDER BY created_at",
                    sessionId);
            if (logs.isEmpty()) return Map.of("error", "会话不存在");

            Map<String, Object> detail = new HashMap<>();
            detail.put("sessionId", sessionId);
            detail.put("messages", logs);
            return detail;
        } catch (Exception e) {
            log.warn("[Admin] 会话详情查询失败: {}", e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    public boolean deleteSession(String sessionId) {
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM routing_call_log WHERE session_id = ?", sessionId);
            jdbcTemplate.update(
                    "DELETE FROM conversation_feedback WHERE session_id = ?", sessionId);
            log.info("[Admin] 删除会话: sessionId={}, deleted={}", sessionId, deleted);
            return deleted > 0;
        } catch (Exception e) {
            log.warn("[Admin] 删除会话失败: {}", e.getMessage());
            return false;
        }
    }

    // ==================== FAQ 管理 ====================

    public List<FaqItem> getFaqs() {
        return List.copyOf(faqStore.values());
    }

    public FaqItem createFaq(Map<String, String> body) {
        String id = String.valueOf(faqIdSeq.getAndIncrement());
        FaqItem item = new FaqItem(
                id, body.getOrDefault("category", "general"),
                body.getOrDefault("question", ""),
                body.getOrDefault("answer", ""),
                body.getOrDefault("keywords", ""),
                0L, LocalDate.now().toString(), LocalDate.now().toString()
        );
        faqStore.put(id, item);
        log.info("[Admin] 新增FAQ: id={}, question={}", id, item.question);
        return item;
    }

    public FaqItem updateFaq(String id, Map<String, String> body) {
        FaqItem existing = faqStore.get(id);
        if (existing == null) return null;
        FaqItem updated = new FaqItem(
                id,
                body.getOrDefault("category", existing.category),
                body.getOrDefault("question", existing.question),
                body.getOrDefault("answer", existing.answer),
                body.getOrDefault("keywords", existing.keywords),
                existing.hitCount,
                existing.createdAt,
                LocalDate.now().toString()
        );
        faqStore.put(id, updated);
        return updated;
    }

    public boolean deleteFaq(String id) {
        return faqStore.remove(id) != null;
    }

    // ==================== 成本看板 ====================

    /**
     * 获取成本统计数据。
     * 基于 routing_call_log 的 latency_ms 和路由信息，
     * 结合各模型的 Token 单价估算成本。
     */
    public Map<String, Object> getCosts() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        try {
            // 按 Agent 汇总调用次数和耗时
            List<Map<String, Object>> byAgent = jdbcTemplate.queryForList(
                    "SELECT routed_agent, COUNT(*) as calls, " +
                    "COALESCE(SUM(latency_ms), 0) as total_latency " +
                    "FROM routing_call_log " +
                    "WHERE created_at >= NOW() - INTERVAL '7 days' " +
                    "GROUP BY routed_agent ORDER BY calls DESC " +
                    dialect.limit(10));
            result.put("byAgent", byAgent);

            // 每日趋势
            List<Map<String, Object>> daily = jdbcTemplate.queryForList(
                    "SELECT " + dialect.dateFunc("created_at") + " as date, " +
                    "COUNT(*) as call_count, " +
                    "COALESCE(SUM(latency_ms), 0) as total_latency " +
                    "FROM routing_call_log " +
                    "WHERE created_at >= " + dialect.dateSub("7") + " " +
                    "GROUP BY " + dialect.dateFunc("created_at") +
                    " ORDER BY date");
            result.put("daily", daily);

            // 总概览
            Map<String, Object> summary = new java.util.LinkedHashMap<>();
            long totalCalls = queryLong(
                    "SELECT COUNT(*) FROM routing_call_log " +
                    "WHERE created_at >= " + dialect.dateSub("7"));
            long totalLatency = queryLong(
                    "SELECT COALESCE(SUM(latency_ms), 0) FROM routing_call_log " +
                    "WHERE created_at >= " + dialect.dateSub("7"));
            summary.put("totalCalls7d", totalCalls);
            summary.put("totalLatencyMs7d", totalLatency);
            summary.put("avgLatencyMs", totalCalls > 0 ? totalLatency / totalCalls : 0);
            result.put("summary", summary);

            log.info("[Admin] 成本数据查询成功: 7d calls={}", totalCalls);
        } catch (Exception e) {
            log.warn("[Admin] 成本数据查询失败: {}", e.getMessage());
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ==================== 工具方法 ====================

    private long queryLong(String sql) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class);
        return result != null ? result : 0L;
    }

    private double queryDouble(String sql) {
        Double result = jdbcTemplate.queryForObject(sql, Double.class);
        return result != null ? result : 0.0;
    }

    private String mapAgentToIntent(String agent) {
        if (agent == null) return "general";
        return switch (agent.toLowerCase()) {
            case "order", "order-service" -> "order";
            case "product", "product-service" -> "product";
            case "general", "general-service" -> "general";
            case "refund" -> "refund";
            default -> agent;
        };
    }

    private void initDefaultFaqs() {
        createFaq(Map.of("category", "order", "question", "怎么查询我的订单？",
                "answer", "请提供您的订单号（格式：ORD-xxx），我可以帮您查询订单状态和物流信息。",
                "keywords", "订单查询,订单状态,物流"));
        createFaq(Map.of("category", "order", "question", "如何申请退款？",
                "answer", "请提供订单号，我可以帮您查询退款政策和流程。退款需满足：已发货/已签收的订单可申请。",
                "keywords", "退款,退货,取消订单"));
        createFaq(Map.of("category", "product", "question", "如何查询商品信息？",
                "answer", "请告诉我商品名称或编码，我可以帮您查询商品详情、价格和库存情况。",
                "keywords", "商品查询,商品信息,价格"));
        createFaq(Map.of("category", "general", "question", "你们有哪些服务？",
                "answer", "我可以帮您查询订单、商品信息，推荐商品，以及回答常见问题。请问您需要什么帮助？",
                "keywords", "服务,功能,帮助"));
    }

    // ==================== 数据类 ====================

    public record AdminStats(
            SatisfactionStats satisfaction,
            List<IntentStats> intents,
            List<DailyStats> daily,
            double transferRate
    ) {
        public static AdminStats empty() {
            return new AdminStats(
                    new SatisfactionStats(0, 0, null, 0, 0, 0, 0, 0),
                    List.of(), List.of(), 0);
        }
    }

    public record SatisfactionStats(
            long total, long rated, Double avgScore,
            long score1, long score2, long score3, long score4, long score5
    ) {}

    public record IntentStats(String intent, long count, long transferCount) {}

    public record DailyStats(String date, long sessionCount, Double avgSatisfaction) {}

    public record FaqItem(
            String id, String category, String question, String answer,
            String keywords, long hitCount,
            String createdAt, String updatedAt
    ) {}
}
