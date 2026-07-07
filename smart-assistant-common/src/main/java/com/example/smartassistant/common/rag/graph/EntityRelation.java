/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.graph;

import java.time.Instant;

/**
 * 实体关系边 — 表示两个实体节点之间的关联关系。
 * <p>
 * 例如：用户"张三" → [下单] → 订单"ORD-001"。
 * </p>
 */
public class EntityRelation {

    private String id;
    private String sourceId;       // 源实体 ID
    private String targetId;       // 目标实体 ID
    private String relationType;   // 关系类型：下单 / 属于 / 引用 / 冲突 ...
    private String description;    // 关系描述
    private Double confidence;     // 抽取置信度 (0~1)
    private String sourceDocId;    // 来源文档 ID
    private Instant createdAt;

    public EntityRelation() {}

    public EntityRelation(String id, String sourceId, String targetId,
                          String relationType, String description, Double confidence,
                          String sourceDocId) {
        this.id = id;
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.relationType = relationType;
        this.description = description;
        this.confidence = confidence;
        this.sourceDocId = sourceDocId;
        this.createdAt = Instant.now();
    }

    // ==================== Getters / Setters ====================

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }

    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public String getSourceDocId() { return sourceDocId; }
    public void setSourceDocId(String sourceDocId) { this.sourceDocId = sourceDocId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
