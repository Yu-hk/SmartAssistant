/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.graph;

import java.time.Instant;

/**
 * 知识图谱实体节点 — 表示一个可被识别和关联的实体。
 * <p>
 * 例如：商品"智能手表"、订单"ORD-001"、用户"张三"等。
 * </p>
 */
public class EntityNode {

    private String id;
    private String name;
    private String type;        // 实体类型：product / order / user / policy / location ...
    private String description;
    private String sourceDocId; // 来源文档 ID
    private String sourceKb;    // 来源知识库名称
    private Instant createdAt;
    private Instant updatedAt;

    public EntityNode() {}

    public EntityNode(String id, String name, String type, String description,
                      String sourceDocId, String sourceKb) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.description = description;
        this.sourceDocId = sourceDocId;
        this.sourceKb = sourceKb;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    // ==================== Getters / Setters ====================

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSourceDocId() { return sourceDocId; }
    public void setSourceDocId(String sourceDocId) { this.sourceDocId = sourceDocId; }

    public String getSourceKb() { return sourceKb; }
    public void setSourceKb(String sourceKb) { this.sourceKb = sourceKb; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
