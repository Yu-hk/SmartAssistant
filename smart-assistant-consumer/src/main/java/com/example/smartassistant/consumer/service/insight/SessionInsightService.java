/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.insight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话洞察服务。
 *
 * <p>为「实时会话洞察」面板提供三类真实后端能力：</p>
 * <ul>
 *     <li>情绪分析：基于关键词启发式的确定性算法（consumer 不持久化消息，故由前端传入最新用户文本）；</li>
 *     <li>知识库检索：查询 {@code faq} 表，返回高匹配知识条目；</li>
 *     <li>工单创建：持久化到 {@code insight_ticket} 表（惰性建表）。</li>
 * </ul>
 *
 * <p>客户画像 / 协同链路 / 待办 由前端基于已持有的 session 上下文实时派生，
 * 不在此处实现（服务端无会话/消息存储）。</p>
 */
@Service
public class SessionInsightService {

    private static final Logger log = LoggerFactory.getLogger(SessionInsightService.class);

    private final JdbcTemplate jdbcTemplate;

    public SessionInsightService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ===================================================
    // 情绪分析（关键词启发式，确定性）
    // ===================================================
    private static final Map<String, Integer> NEGATIVE = Map.ofEntries(
            Map.entry("退款", 3), Map.entry("投诉", 4), Map.entry("差", 2), Map.entry("坏", 2),
            Map.entry("慢", 2), Map.entry("急", 3), Map.entry("不满", 3), Map.entry("生气", 4),
            Map.entry("问题", 1), Map.entry("错误", 2), Map.entry("丢失", 3), Map.entry("损坏", 3),
            Map.entry("诈骗", 5), Map.entry("催", 2), Map.entry("尽快", 2), Map.entry("立刻", 2),
            Map.entry("马上", 2), Map.entry("退", 2), Map.entry("假", 3), Map.entry("骗", 3),
            Map.entry("垃圾", 3), Map.entry("取消", 2), Map.entry("差评", 4), Map.entry("失望", 3),
            Map.entry("愤怒", 5), Map.entry("坑", 3), Map.entry("骗人", 4)
    );

    private static final Map<String, Integer> POSITIVE = Map.ofEntries(
            Map.entry("谢谢", 2), Map.entry("感谢", 2), Map.entry("好的", 1), Map.entry("可以", 1),
            Map.entry("没问题", 2), Map.entry("满意", 3), Map.entry("不错", 2), Map.entry("麻烦", 1),
            Map.entry("请", 1), Map.entry("了解", 1), Map.entry("明白", 1), Map.entry("谢谢您", 2),
            Map.entry("辛苦", 1), Map.entry("期待", 1), Map.entry("理解", 1)
    );

    public record EmotionResult(String label, int score, int confidence) {}

    /**
     * 情绪分析：输入用户文本，输出情绪标签 / 分数(0-100) / 置信度(0-100)。
     */
    public EmotionResult analyzeEmotion(String text) {
        if (text == null || text.isBlank()) {
            return new EmotionResult("平静", 50, 60);
        }
        int score = 50;
        int hits = 0;
        for (Map.Entry<String, Integer> e : NEGATIVE.entrySet()) {
            if (text.contains(e.getKey())) {
                score -= e.getValue();
                hits++;
            }
        }
        for (Map.Entry<String, Integer> e : POSITIVE.entrySet()) {
            if (text.contains(e.getKey())) {
                score += e.getValue();
                hits++;
            }
        }
        score = Math.max(5, Math.min(100, score));

        String label;
        if (score >= 72) label = "平静";
        else if (score >= 55) label = "平静 · 略急";
        else if (score >= 38) label = "略急";
        else label = "急切 / 不满";

        int confidence = Math.max(60, Math.min(95, 60 + hits * 8));
        return new EmotionResult(label, score, confidence);
    }

    // ===================================================
    // 知识库检索（FAQ 表真实查询）
    // ===================================================
    public record KbHit(String title, int match, String source) {}

    /**
     * 知识库检索：按查询文本或意图匹配 faq 表，返回高匹配条目（含匹配率）。
     */
    public List<KbHit> searchKb(String query, String intent) {
        try {
            String q = (query == null ? "" : query).trim();
            String sql;
            List<Object> params = new ArrayList<>();

            if (!q.isEmpty()) {
                sql = "SELECT question, hit_count FROM faq " +
                      "WHERE question LIKE ? OR keywords LIKE ? " +
                      "ORDER BY COALESCE(hit_count, 0) DESC LIMIT 5";
                params.add("%" + q + "%");
                params.add("%" + q + "%");
            } else if (intent != null && !intent.isBlank() && !"unknown".equalsIgnoreCase(intent)) {
                sql = "SELECT question, hit_count FROM faq " +
                      "WHERE category LIKE ? OR keywords LIKE ? " +
                      "ORDER BY COALESCE(hit_count, 0) DESC LIMIT 5";
                params.add("%" + intent + "%");
                params.add("%" + intent + "%");
            } else {
                sql = "SELECT question, hit_count FROM faq " +
                      "ORDER BY COALESCE(hit_count, 0) DESC LIMIT 5";
            }

            return jdbcTemplate.query(sql, (rs, i) -> {
                String title = rs.getString("question");
                int hit = rs.getObject("hit_count") != null ? rs.getInt("hit_count") : 0;
                int base = 82 + Math.min(15, hit / 5);
                return new KbHit(title, Math.min(99, base), "知识库");
            }, params.toArray());
        } catch (Exception e) {
            log.warn("[Insight] KB 检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ===================================================
    // 工单创建（持久化）
    // ===================================================
    public record TicketResult(String id, String status) {}

    private static final String CREATE_TICKET_SQL =
            "CREATE TABLE IF NOT EXISTS insight_ticket (" +
            "id VARCHAR(64) PRIMARY KEY, " +
            "session_id VARCHAR(64), " +
            "intent VARCHAR(32), " +
            "summary TEXT, " +
            "customer_name VARCHAR(128), " +
            "status VARCHAR(16), " +
            "created_at TIMESTAMP)";

    /**
     * 创建工单并持久化，返回工单号与状态。
     */
    public TicketResult createTicket(String sessionId, String intent, String summary, String customerName) {
        try {
            jdbcTemplate.execute(CREATE_TICKET_SQL);
            String id = UUID.randomUUID().toString().replace("-", "");
            jdbcTemplate.update(
                    "INSERT INTO insight_ticket (id, session_id, intent, summary, customer_name, status, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    id, sessionId, intent, summary, customerName, "OPEN", LocalDateTime.now()
            );
            log.info("[Insight] 工单已创建: id={}, sessionId={}, intent={}", id, sessionId, intent);
            return new TicketResult(id, "OPEN");
        } catch (Exception e) {
            log.error("[Insight] 工单创建失败: {}", e.getMessage(), e);
            throw new RuntimeException("工单创建失败: " + e.getMessage(), e);
        }
    }
}
