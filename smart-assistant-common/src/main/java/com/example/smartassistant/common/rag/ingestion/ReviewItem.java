/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion;

import com.example.smartassistant.common.rag.KnowledgeDocument;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 复核队列条目（REQ-1 脏数据拦截落库载体）。
 * <p>对应 {@code knowledge_review_queue} 表：{@code id, raw_payload, reason, source_type,
 * submitted_by, status, reviewed_by, reviewed_at, created_at}。</p>
 */
public class ReviewItem {

    public static final String STATUS_REVIEW = "REVIEW";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    private final String id;
    private final String rawPayload;
    private final String reason;
    private final String code;
    private final String sourceType;
    private final String submittedBy;
    private final String status;
    private final long createdAt;

    private ReviewItem(Builder b) {
        this.id = b.id;
        this.rawPayload = b.rawPayload;
        this.reason = b.reason;
        this.code = b.code;
        this.sourceType = b.sourceType;
        this.submittedBy = b.submittedBy;
        this.status = b.status;
        this.createdAt = b.createdAt;
    }

    public static ReviewItem of(KnowledgeDocument doc, String reason, String code, String submittedBy) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", doc.getId());
        payload.put("title", doc.getTitle());
        payload.put("content", doc.getContent());
        payload.put("category", doc.getCategory());
        payload.put("sourceType", doc.getSourceType());
        payload.put("tenantId", doc.getTenantId());
        payload.put("version", doc.getVersion());
        payload.put("indexVersion", doc.getIndexVersion());
        try {
            payload.put("serialized", new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(doc));
        } catch (Exception ignored) {
            // 序列化失败不影响入队，serialized 字段留空
        }
        String raw;
        try {
            raw = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (Exception e) {
            raw = "{\"id\":\"" + doc.getId() + "\"}";
        }
        return builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .rawPayload(raw)
                .reason(reason)
                .code(code)
                .sourceType(doc.getSourceType())
                .submittedBy(submittedBy)
                .status(STATUS_REVIEW)
                .createdAt(System.currentTimeMillis())
                .build();
    }

    public static Builder builder() { return new Builder(); }

    public String getId() { return id; }
    public String getRawPayload() { return rawPayload; }
    public String getReason() { return reason; }
    public String getCode() { return code; }
    public String getSourceType() { return sourceType; }
    public String getSubmittedBy() { return submittedBy; }
    public String getStatus() { return status; }
    public long getCreatedAt() { return createdAt; }

    public Builder toBuilder() {
        return builder().id(id).rawPayload(rawPayload).reason(reason).code(code)
                .sourceType(sourceType).submittedBy(submittedBy).status(status).createdAt(createdAt);
    }

    public static class Builder {
        private String id;
        private String rawPayload;
        private String reason;
        private String code;
        private String sourceType;
        private String submittedBy;
        private String status = STATUS_REVIEW;
        private long createdAt = System.currentTimeMillis();

        public Builder id(String v) { this.id = v; return this; }
        public Builder rawPayload(String v) { this.rawPayload = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }
        public Builder code(String v) { this.code = v; return this; }
        public Builder sourceType(String v) { this.sourceType = v; return this; }
        public Builder submittedBy(String v) { this.submittedBy = v; return this; }
        public Builder status(String v) { this.status = v; return this; }
        public Builder createdAt(long v) { this.createdAt = v; return this; }
        public ReviewItem build() { return new ReviewItem(this); }
    }
}
