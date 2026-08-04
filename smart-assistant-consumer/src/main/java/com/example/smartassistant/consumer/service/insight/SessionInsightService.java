/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.insight;

import com.example.smartassistant.common.memory.AgentMemoryService;
import com.example.smartassistant.common.memory.EntityProfileService;
import com.example.smartassistant.common.rag.KnowledgeBase;
import com.example.smartassistant.common.rag.KnowledgeDocument;
import com.example.smartassistant.common.rag.KnowledgeHit;
import com.example.smartassistant.consumer.entity.UserProfile;
import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 会话洞察服务。
 *
 * <p>为「实时会话洞察」面板提供四类后端能力：</p>
 * <ul>
 *     <li>情绪分析：基于关键词启发式的确定性算法（consumer 不持久化消息，故由前端传入最新用户文本）；</li>
 *     <li>知识库检索：查询 {@code faq} 表��返回高匹配知识条目；</li>
 *     <li>工单创建：持久化到 {@code insight_ticket} 表（惰性建表）；</li>
 *     <li>客户 360° 画像：聚合 UserProfile + EntityProfile + AgentMemory + 情绪历史。</li>
 * </ul>
 */
@Service
public class SessionInsightService {

    private static final Logger log = LoggerFactory.getLogger(SessionInsightService.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // 情绪历史弱引用缓存 —— userId → 情绪快照列表（上限 50 条 / 用户）
    private static final Map<Long, List<CustomerProfileVO.EmotionSnapshot>> EMOTION_HISTORY =
            Collections.synchronizedMap(new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, List<CustomerProfileVO.EmotionSnapshot>> eldest) {
                    return size() > 1024; // 最多缓存 1024 个用户
                }
            });

    private final JdbcTemplate jdbcTemplate;
    private final UserProfileService userProfileService;
    private final EntityProfileService entityProfileService;
    private final AgentMemoryService agentMemoryService;
    private final KnowledgeBase knowledgeBase;

    public SessionInsightService(JdbcTemplate jdbcTemplate,
                                  UserProfileService userProfileService,
                                  EntityProfileService entityProfileService,
                                  AgentMemoryService agentMemoryService,
                                  KnowledgeBase knowledgeBase) {
        this.jdbcTemplate = jdbcTemplate;
        this.userProfileService = userProfileService;
        this.entityProfileService = entityProfileService;
        this.agentMemoryService = agentMemoryService;
        this.knowledgeBase = knowledgeBase;
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
     * 同时记录情绪快照到缓存，供客户 360° 使用。
     */
    public EmotionResult analyzeEmotion(String text) {
        return analyzeEmotion(text, null, null);
    }

    /**
     * 情绪分析（完整版），接收可选 userId 用于情绪历史记录。
     */
    public EmotionResult analyzeEmotion(String text, Long userId, String triggerTopic) {
        if (text == null || text.isBlank()) {
            EmotionResult r = new EmotionResult("平静", 50, 60);
            if (userId != null) recordEmotion(userId, r, triggerTopic);
            return r;
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
        EmotionResult r = new EmotionResult(label, score, confidence);
        if (userId != null) recordEmotion(userId, r, triggerTopic);
        return r;
    }

    // ===================================================
    // 知识库检索（FAQ 表真实查询）
    // ===================================================
    public record KbHit(String title, int match, String source) {}

    /**
     * 知识库检索（⭐ P2-B：RAG 向量检索优先，SQL LIKE 兜底）。
     *
     * <p>优先调用 {@link KnowledgeBase#searchWithParentExpansion(String, int)} 进行语义向量召回；
     * 当 RAG 不可用（KB 为空或未播种）或抛出异常时，降级到既有 faq 表的 SQL LIKE，
     * 保证「实时会话洞察」右栏始终有数据，不出现空白。</p>
     */
    public List<KbHit> searchKb(String query, String intent) {
        String q = (query == null ? "" : query).trim();

        // 1. RAG 向量检索（优先）
        try {
            if (knowledgeBase != null && !q.isEmpty()) {
                List<KnowledgeHit> hits = knowledgeBase.searchWithParentExpansion(q, 5);
                if (hits != null && !hits.isEmpty()) {
                    return hits.stream()
                            .map(h -> {
                                KnowledgeDocument doc = h.getDocument();
                                String title = (doc != null && doc.getTitle() != null && !doc.getTitle().isBlank())
                                        ? doc.getTitle()
                                        : (q.length() > 20 ? q.substring(0, 20) + "…" : q);
                                int match = (int) Math.max(0, Math.min(99, Math.round(h.getScore() * 100)));
                                String source = (doc != null && doc.getSourceUrl() != null && !doc.getSourceUrl().isBlank())
                                        ? doc.getSourceUrl()
                                        : (doc != null && doc.getCategory() != null ? doc.getCategory() : "知识库");
                                return new KbHit(title, match, source);
                            })
                            .collect(Collectors.toList());
                }
            }
        } catch (Exception e) {
            log.warn("[Insight] RAG 检索失败，降级 SQL: {}", e.getMessage());
        }

        // 2. SQL LIKE 兜底（复用既有 faq 表逻辑）
        return searchKbBySql(q, intent);
    }

    /** 既有 faq 表 SQL LIKE 检索（RAG 兜底层，参数化防注入）。 */
    private List<KbHit> searchKbBySql(String q, String intent) {
        try {
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
            log.warn("[Insight] KB 检索(SQL)失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ===================================================
    // 工单生命周期（持久化）
    // ===================================================
    /** 工单状态机：OPEN → IN_PROGRESS / PENDING → RESOLVED → CLOSED */
    public static final List<String> TICKET_STATUSES =
            List.of("OPEN", "IN_PROGRESS", "PENDING", "RESOLVED", "CLOSED");

    /**
     * 前端工单面板展示视图（含生命周期字段）。
     * closedAt / resolution 在工单关闭前为 null / 空串。
     */
    public record TicketView(
            String id, Long userId, String sessionId, String intent, String summary,
            String customerName, String status,
            String createdAt, String updatedAt, String closedAt, String resolution
    ) {}

    private static final String CREATE_TICKET_SQL =
            "CREATE TABLE IF NOT EXISTS insight_ticket (" +
            "id VARCHAR(64) PRIMARY KEY, " +
            "user_id BIGINT, " +
            "session_id VARCHAR(64), " +
            "intent VARCHAR(32), " +
            "summary TEXT, " +
            "customer_name VARCHAR(128), " +
            "status VARCHAR(16), " +
            "created_at TIMESTAMP, " +
            "updated_at TIMESTAMP, " +
            "closed_at TIMESTAMP, " +
            "resolution TEXT)";

    /** 幂等迁移：为已存在的旧表补齐生命周期列（updated_at / closed_at / resolution） */
    private void ensureTicketSchema() {
        try {
            jdbcTemplate.execute("ALTER TABLE insight_ticket ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP");
            jdbcTemplate.execute("ALTER TABLE insight_ticket ADD COLUMN IF NOT EXISTS closed_at TIMESTAMP");
            jdbcTemplate.execute("ALTER TABLE insight_ticket ADD COLUMN IF NOT EXISTS resolution TEXT");
            jdbcTemplate.execute("ALTER TABLE insight_ticket ADD COLUMN IF NOT EXISTS user_id BIGINT");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_insight_ticket_user_created " +
                    "ON insight_ticket (user_id, created_at DESC)");
        } catch (Exception e) {
            log.warn("[Insight] 工单表迁移跳过: {}", e.getMessage());
        }
    }

    /**
     * 创建工单并持久化，返回完整工单视图。
     */
    public TicketView createTicket(
            Long userId, String sessionId, String intent, String summary, String customerName) {
        try {
            jdbcTemplate.execute(CREATE_TICKET_SQL);
            ensureTicketSchema();
            String id = UUID.randomUUID().toString().replace("-", "");
            LocalDateTime now = LocalDateTime.now();
            jdbcTemplate.update(
                    "INSERT INTO insight_ticket (id, user_id, session_id, intent, summary, customer_name, status, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, userId, sessionId, intent, summary, customerName, "OPEN", now, now
            );
            log.info("[Insight] 工单已创建: id={}, sessionId={}, intent={}", id, sessionId, intent);
            return new TicketView(id, userId, sessionId, intent, summary, customerName, "OPEN",
                    now.format(DT_FMT), now.format(DT_FMT), null, null);
        } catch (Exception e) {
            log.error("[Insight] 工单创建失败: {}", e.getMessage(), e);
            throw new RuntimeException("工单创建失败: " + e.getMessage(), e);
        }
    }

    /**
     * 推进工单状态（生命周期）。
     * 允许在合法状态集合内任意流转，但 CLOSED 视为终态，需通过 {@link #closeTicket} 进入。
     */
    public TicketView updateTicketStatus(String ticketId, Long userId, boolean admin, String status) {
        if (ticketId == null || ticketId.isBlank()) throw new IllegalArgumentException("ticketId 不能为空");
        String norm = (status == null) ? "" : status.trim().toUpperCase();
        if (!TICKET_STATUSES.contains(norm) || "CLOSED".equals(norm)) {
            throw new IllegalArgumentException("非法工单状态（CLOSED 请使用关闭接口）: " + status);
        }
        try {
            ensureTicketSchema();
            int n = admin
                    ? jdbcTemplate.update(
                            "UPDATE insight_ticket SET status = ?, updated_at = ? WHERE id = ?",
                            norm, LocalDateTime.now(), ticketId)
                    : jdbcTemplate.update(
                            "UPDATE insight_ticket SET status = ?, updated_at = ? WHERE id = ? AND user_id = ?",
                            norm, LocalDateTime.now(), ticketId, userId);
            if (n == 0) throw new RuntimeException("工单不存在: " + ticketId);
            return getTicket(ticketId, userId, admin);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("工单状态更新失败: " + e.getMessage(), e);
        }
    }

    /**
     * 关闭工单（终态）。可附带处理结论，记录 closed_at。
     */
    public TicketView closeTicket(String ticketId, Long userId, boolean admin, String resolution) {
        if (ticketId == null || ticketId.isBlank()) throw new IllegalArgumentException("ticketId 不能为空");
        try {
            ensureTicketSchema();
            LocalDateTime now = LocalDateTime.now();
            int n = admin
                    ? jdbcTemplate.update(
                            "UPDATE insight_ticket SET status = 'CLOSED', closed_at = ?, updated_at = ?, resolution = ? WHERE id = ?",
                            now, now, resolution == null ? "" : resolution, ticketId)
                    : jdbcTemplate.update(
                            "UPDATE insight_ticket SET status = 'CLOSED', closed_at = ?, updated_at = ?, resolution = ? " +
                            "WHERE id = ? AND user_id = ?",
                            now, now, resolution == null ? "" : resolution, ticketId, userId);
            if (n == 0) throw new RuntimeException("工单不存在: " + ticketId);
            return getTicket(ticketId, userId, admin);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("工单关闭失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按 sessionId 或 customerName 查询工单列表（前端面板展示）。
     * 两个参数均为可选，同时为空时返回最近 100 条。
     */
    public List<TicketView> listTickets(
            Long userId, boolean admin, String sessionId, String customerName) {
        try {
            ensureTicketSchema();
            String sql = "SELECT id, user_id, session_id, intent, summary, customer_name, status, " +
                    "created_at, updated_at, closed_at, resolution FROM insight_ticket WHERE 1=1";
            List<Object> args = new ArrayList<>();
            if (!admin) {
                sql += " AND user_id = ?"; args.add(userId);
            }
            if (sessionId != null && !sessionId.isBlank()) {
                sql += " AND session_id = ?"; args.add(sessionId);
            }
            if (customerName != null && !customerName.isBlank()) {
                sql += " AND customer_name = ?"; args.add(customerName);
            }
            sql += " ORDER BY created_at DESC LIMIT 100";
            return jdbcTemplate.query(sql, (rs, rowNum) -> new TicketView(
                    rs.getString("id"),
                    rs.getObject("user_id", Long.class),
                    rs.getString("session_id"),
                    rs.getString("intent"),
                    rs.getString("summary"),
                    rs.getString("customer_name"),
                    rs.getString("status"),
                    formatTs(rs.getTimestamp("created_at")),
                    formatTs(rs.getTimestamp("updated_at")),
                    formatTs(rs.getTimestamp("closed_at")),
                    rs.getString("resolution")
            ), args.toArray());
        } catch (Exception e) {
            log.warn("[Insight] 工单查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 单工单详情 */
    public TicketView getTicket(String ticketId, Long userId, boolean admin) {
        try {
            ensureTicketSchema();
            String sql = "SELECT id, user_id, session_id, intent, summary, customer_name, status, " +
                    "created_at, updated_at, closed_at, resolution FROM insight_ticket WHERE id = ? " +
                    (admin ? "" : "AND user_id = ?");
            Object[] args = admin ? new Object[]{ticketId} : new Object[]{ticketId, userId};
            return jdbcTemplate.queryForObject(sql,
                    (rs, rowNum) -> new TicketView(
                            rs.getString("id"),
                            rs.getObject("user_id", Long.class),
                            rs.getString("session_id"),
                            rs.getString("intent"),
                            rs.getString("summary"),
                            rs.getString("customer_name"),
                            rs.getString("status"),
                            formatTs(rs.getTimestamp("created_at")),
                            formatTs(rs.getTimestamp("updated_at")),
                            formatTs(rs.getTimestamp("closed_at")),
                            rs.getString("resolution")
                    ), args);
        } catch (Exception e) {
            log.warn("[Insight] 工单查询失败: {}", e.getMessage());
            return null;
        }
    }

    private static String formatTs(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime().format(DT_FMT);
    }

    // ===================================================
    // 情绪历史追踪（内存缓存，供客户 360° API 消费）
    // ===================================================

    /**
     * 记录情绪快照到缓存。每条记录包含时间戳、分数、标签、触发话题。
     * 每用户上限 50 条（FIFO 淘汰）。
     */
    private void recordEmotion(Long userId, EmotionResult r, String triggerTopic) {
        if (userId == null) return;
        String ts = LocalDateTime.now().format(DT_FMT);
        var snap = new CustomerProfileVO.EmotionSnapshot(ts, r.score(), r.label(), triggerTopic);
        EMOTION_HISTORY.compute(userId, (id, list) -> {
            if (list == null) list = new ArrayList<>();
            list.add(snap);
            if (list.size() > 50) list.remove(0); // FIFO 保留最近 50 条
            return list;
        });
        // ⭐ P2-A：情绪数据回流到 UserProfile（持久化，跨重启保留）
        try {
            userProfileService.recordEmotion(userId, r.label(), r.score());
        } catch (Exception e) {
            log.warn("[Insight] 情绪回流到画像失败: {}", e.getMessage());
        }
        log.debug("[Insight] 情绪快照记录: userId={}, label={}, score={}", userId, r.label(), r.score());
    }

    /** 查询用户的情绪历史（最近 50 条，从旧到新）。 */
    public List<CustomerProfileVO.EmotionSnapshot> getEmotionHistory(Long userId) {
        List<CustomerProfileVO.EmotionSnapshot> list = EMOTION_HISTORY.get(userId);
        return list != null ? List.copyOf(list) : List.of();
    }

    // ===================================================
    // 客户 360° 画像聚合（P0）
    // ===================================================

    /**
     * 聚合用户画像数据为统一 VO，供前端 InsightPanel「客户画像」卡片展示。
     *
     * <p>数据来源：</p>
     * <ul>
     *     <li>{@link UserProfileService} — preferences.json（偏好/意图分布/查询次数）</li>
     *     <li>{@link EntityProfileService} — Redis KV（preference/fear/hobby/location/name）</li>
     *     <li>{@link AgentMemoryService} — 各 Agent 领域记忆（坐席笔记摘要）</li>
     *     <li>{@link #getEmotionHistory} — 内存缓存情绪快照</li>
     * </ul>
     */
    public CustomerProfileVO getCustomerProfile(Long userId, String userName) {
        if (userId == null) {
            return new CustomerProfileVO(
                    userName != null ? userName : "访客", 0,
                    Map.of(), Map.of(), List.of(), List.of(), "", List.of(),
                    Map.of(), 0, 0, null, 0, 0, 0, null, List.of(), getEmotionHistory(null), List.of());
        }

        // 1. UserProfile 偏好
        UserProfile profile = userProfileService.getProfile(userId);
        Map<String, Integer> intentDist = profile != null ? profile.getIntentDistribution() : Map.of();
        List<String> foodPrefs = profile != null && profile.getFoodPreferencesArray() != null
                ? List.of(profile.getFoodPreferencesArray()) : List.of();
        List<String> travelPrefs = profile != null && profile.getTravelPreferencesArray() != null
                ? List.of(profile.getTravelPreferencesArray()) : List.of();
        String budget = profile != null ? profile.getBudgetRange() : "";
        List<String> diet = profile != null && profile.getDietaryRestrictionsArray() != null
                ? List.of(profile.getDietaryRestrictionsArray()) : List.of();
        Map<String, Integer> weights = profile != null ? profile.getPreferenceWeightsMap() : Map.of();
        int totalQueries = profile != null ? profile.getTotalQueries() : 0;

        // 从意图分布推断升级/投诉次数
        int escalationCount = intentDist.getOrDefault("complaint", 0)
                + intentDist.getOrDefault("tech", 0) / 3;
        int complaintCount = intentDist.getOrDefault("refund", 0)
                + intentDist.getOrDefault("complaint", 0);

        // 2. EntityProfile Redis 事实
        Map<String, String> entityFacts = entityProfileService.getAll(userId);

        // 3. AgentMemory 坐席笔记摘要
        List<String> agentMemories = new ArrayList<>();
        for (String agent : new String[]{"order", "product", "general"}) {
            String formatted = agentMemoryService.getAllFormatted(String.valueOf(userId), agent);
            if (formatted != null && !formatted.isBlank()) {
                String summary = formatted.length() > 100
                        ? formatted.substring(0, 100) + "…"
                        : formatted;
                agentMemories.add("【" + agent + "】" + summary);
            }
        }

        // 4. 情绪历史
        List<CustomerProfileVO.EmotionSnapshot> emotions = getEmotionHistory(userId);

        // ⭐ P2-A：持久化情绪聚合（来自 UserProfile 回流）
        String lastEmotionLabel = profile != null ? profile.getLastEmotionLabel() : null;
        int lastEmotionScore = profile != null ? profile.getLastEmotionScore() : 0;
        int negativeTouchCount = profile != null ? profile.getNegativeTouchCount() : 0;
        int positiveTouchCount = profile != null ? profile.getPositiveTouchCount() : 0;
        Double emotionAvgScore = profile != null ? profile.getEmotionAvgScore() : null;

        // ⭐ P2-C：隐藏关键信息（潜在需求/隐性信号）
        List<String> keyInsights = profile != null && profile.getKeyInsightsArray() != null
                ? Arrays.asList(profile.getKeyInsightsArray()) : List.of();

        String name = userName;
        if ((name == null || name.isBlank()) && entityFacts.containsKey("name")) {
            name = entityFacts.get("name");
        }
        if (name == null || name.isBlank()) name = "访客";

        return new CustomerProfileVO(
                name, totalQueries, intentDist, entityFacts,
                foodPrefs, travelPrefs, budget, diet, weights,
                escalationCount, complaintCount,
                lastEmotionLabel, lastEmotionScore, negativeTouchCount, positiveTouchCount, emotionAvgScore,
                agentMemories, emotions, keyInsights);
    }
}
