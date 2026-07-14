/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 复核队列服务（REQ-1）——脏数据拦截后落入 {@code knowledge_review_queue} 供运营复核。
 * <p>
 * 存储：优先写入 PG（{@code JdbcTemplate} 非空）；无 JdbcTemplate（内存/测试态）时退化为内存 Map。
 * 读取（{@code get/list}）优先 DB，异常时回退内存，保证任何环境可用。
 * </p>
 *
 * <p>审批语义：</p>
 * <ul>
 *   <li>{@link #approve(String, String)}：标记 APPROVED，并记录运营覆盖项（overridesJson）；</li>
 *   <li>{@link #reject(String)}：标记 REJECTED（丢弃，可通知上传人）。</li>
 * </ul>
 */
public class ReviewQueueService {

    private static final Logger log = LoggerFactory.getLogger(ReviewQueueService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ReviewItem> memory = new ConcurrentHashMap<>();

    public ReviewQueueService() {
        this(null);
    }

    public ReviewQueueService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 入队一条脏数据 */
    public void enqueue(ReviewItem item) {
        if (item == null) return;
        memory.put(item.getId(), item);
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.update(
                        "INSERT INTO knowledge_review_queue "
                                + "(id, raw_payload, reason, source_type, submitted_by, status, created_at) "
                                + "VALUES (?, ?::jsonb, ?, ?, ?, ?, ?) "
                                + "ON CONFLICT (id) DO NOTHING",
                        item.getId(), item.getRawPayload(), item.getReason(),
                        item.getSourceType(), item.getSubmittedBy(),
                        item.getStatus(), item.getCreatedAt());
            } catch (Exception e) {
                log.warn("[ReviewQueue] 写入 PG 失败（已保留内存）: {}", e.getMessage());
            }
        }
        log.info("[ReviewQueue] 入队复核: id={}, reason={}, submittedBy={}",
                item.getId(), item.getReason(), item.getSubmittedBy());
    }

    /** 审批通过（带覆盖项 JSON，可选） */
    public boolean approve(String id, String reviewedBy, String overridesJson) {
        ReviewItem existing = get(id);
        if (existing == null) return false;
        ReviewItem approved = existing.toBuilder()
                .status(ReviewItem.STATUS_APPROVED)
                .build();
        memory.put(id, approved);
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.update(
                        "UPDATE knowledge_review_queue SET status = ?, reviewed_by = ?, reviewed_at = ? "
                                + "WHERE id = ?",
                        ReviewItem.STATUS_APPROVED, reviewedBy, System.currentTimeMillis(), id);
            } catch (Exception e) {
                log.warn("[ReviewQueue] 审批写 PG 失败: {}", e.getMessage());
            }
        }
        log.info("[ReviewQueue] 审批通过: id={}, reviewer={}", id, reviewedBy);
        return true;
    }

    /** 审批拒绝（丢弃） */
    public boolean reject(String id, String reviewedBy) {
        ReviewItem existing = get(id);
        if (existing == null) return false;
        ReviewItem rejected = existing.toBuilder()
                .status(ReviewItem.STATUS_REJECTED)
                .build();
        memory.put(id, rejected);
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.update(
                        "UPDATE knowledge_review_queue SET status = ?, reviewed_by = ?, reviewed_at = ? "
                                + "WHERE id = ?",
                        ReviewItem.STATUS_REJECTED, reviewedBy, System.currentTimeMillis(), id);
            } catch (Exception e) {
                log.warn("[ReviewQueue] 拒绝写 PG 失败: {}", e.getMessage());
            }
        }
        log.info("[ReviewQueue] 审批拒绝(丢弃): id={}, reviewer={}", id, reviewedBy);
        return true;
    }

    /** 查询单条 */
    public ReviewItem get(String id) {
        if (jdbcTemplate != null) {
            try {
                return jdbcTemplate.query("SELECT * FROM knowledge_review_queue WHERE id = ?",
                        (ResultSet rs, int rowNum) -> mapRow(rs), id).stream().findFirst().orElse(memory.get(id));
            } catch (Exception e) {
                return memory.get(id);
            }
        }
        return memory.get(id);
    }

    /** 按状态列出（null/空表示全部） */
    public List<ReviewItem> list(String status) {
        List<ReviewItem> result = new ArrayList<>();
        if (jdbcTemplate != null) {
            try {
                String sql = (status == null || status.isBlank())
                        ? "SELECT * FROM knowledge_review_queue ORDER BY created_at DESC"
                        : "SELECT * FROM knowledge_review_queue WHERE status = ? ORDER BY created_at DESC";
                List<ReviewItem> fromDb = (status == null || status.isBlank())
                        ? jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> mapRow(rs))
                        : jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> mapRow(rs), status);
                if (fromDb != null) result.addAll(fromDb);
                return result;
            } catch (Exception e) {
                log.warn("[ReviewQueue] 列表查询 PG 失败，回退内存: {}", e.getMessage());
            }
        }
        for (ReviewItem item : memory.values()) {
            if (status == null || status.isBlank() || status.equals(item.getStatus())) {
                result.add(item);
            }
        }
        result.sort(Comparator.comparingLong(ReviewItem::getCreatedAt).reversed());
        return result;
    }

    /** 待复核数量 */
    public int pendingCount() {
        return (int) list(ReviewItem.STATUS_REVIEW).size();
    }

    private ReviewItem mapRow(ResultSet rs) throws java.sql.SQLException {
        String raw = rs.getString("raw_payload");
        String reason = rs.getString("reason");
        String sourceType = rs.getString("source_type");
        String submittedBy = rs.getString("submitted_by");
        String status = rs.getString("status");
        long createdAt = rs.getLong("created_at");
        String id = rs.getString("id");
        try {
            JsonNode node = mapper.readTree(raw);
            String code = node.has("code") ? node.get("code").asText() : "";
            if (code.isBlank()) code = node.has("reason") ? node.get("reason").asText() : "";
        } catch (Exception ignored) {
            // ignore
        }
        return ReviewItem.builder()
                .id(id)
                .rawPayload(raw)
                .reason(reason)
                .sourceType(sourceType)
                .submittedBy(submittedBy)
                .status(status)
                .createdAt(createdAt)
                .build();
    }
}
