/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 知识图谱服务 — 实体与关系的存取与管理。
 * <p>
 * 当前为内存实现，用于框架原型验证。
 * 生产环境应迁移至 PG 邻接表或专用图数据库（Neo4j）。
 * 该服务的核心消费者是 {@code CrossDocumentConflictResolver}（通过实体关系辅助冲突检测）
 * 和 {@link com.example.smartassistant.common.rag.KnowledgeBase}（检索时关联实体）。
 * </p>
 *
 * <h3>PG 表结构（生产迁移目标）</h3>
 * <pre>{@code
 * -- 实体节点表
 * CREATE TABLE IF NOT EXISTS entity_nodes (
 *     id          VARCHAR(128) PRIMARY KEY,
 *     name        VARCHAR(512) NOT NULL,
 *     type        VARCHAR(64)  NOT NULL,
 *     description TEXT,
 *     source_doc_id VARCHAR(128),
 *     source_kb   VARCHAR(64),
 *     created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *     updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 * );
 *
 * -- 实体关系边表
 * CREATE TABLE IF NOT EXISTS entity_relations (
 *     id            VARCHAR(128) PRIMARY KEY,
 *     source_id     VARCHAR(128) NOT NULL REFERENCES entity_nodes(id),
 *     target_id     VARCHAR(128) NOT NULL REFERENCES entity_nodes(id),
 *     relation_type VARCHAR(64)  NOT NULL,
 *     description   TEXT,
 *     confidence    REAL DEFAULT 0.0,
 *     source_doc_id VARCHAR(128),
 *     created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 * );
 *
 * -- 按类型查询加速
 * CREATE INDEX IF NOT EXISTS idx_entity_nodes_type ON entity_nodes(type);
 * CREATE INDEX IF NOT EXISTS idx_entity_relations_source ON entity_relations(source_id);
 * CREATE INDEX IF NOT EXISTS idx_entity_relations_type ON entity_relations(relation_type);
 * }</pre>
 * </p>
 */
public class KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphService.class);

    private final List<EntityNode> nodes = new CopyOnWriteArrayList<>();
    private final List<EntityRelation> edges = new CopyOnWriteArrayList<>();

    private EntityExtractor extractor = new NoopEntityExtractor();

    /** 设置实体关系抽取器 */
    public void setExtractor(EntityExtractor extractor) {
        this.extractor = extractor != null ? extractor : new NoopEntityExtractor();
    }

    // ==================== 实体操作 ====================

    public void addNode(EntityNode node) {
        if (node != null && node.getId() != null) {
            nodes.removeIf(n -> n.getId().equals(node.getId()));
            nodes.add(node);
        }
    }

    public void addNodes(List<EntityNode> entities) {
        if (entities != null) entities.forEach(this::addNode);
    }

    public EntityNode getNode(String id) {
        return nodes.stream().filter(n -> id.equals(n.getId())).findFirst().orElse(null);
    }

    public List<EntityNode> searchNodes(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();
        String lower = keyword.toLowerCase();
        return nodes.stream()
                .filter(n -> n.getName().toLowerCase().contains(lower)
                        || n.getType().toLowerCase().contains(lower)
                        || (n.getDescription() != null && n.getDescription().toLowerCase().contains(lower)))
                .toList();
    }

    public List<EntityNode> getAllNodes() { return List.copyOf(nodes); }

    // ==================== 关系操作 ====================

    public void addRelation(EntityRelation relation) {
        if (relation != null && relation.getId() != null) {
            edges.removeIf(e -> e.getId().equals(relation.getId()));
            edges.add(relation);
        }
    }

    public void addRelations(List<EntityRelation> relations) {
        if (relations != null) relations.forEach(this::addRelation);
    }

    public List<EntityRelation> getRelationsForNode(String nodeId) {
        if (nodeId == null) return List.of();
        return edges.stream()
                .filter(e -> nodeId.equals(e.getSourceId()) || nodeId.equals(e.getTargetId()))
                .toList();
    }

    public List<EntityRelation> getAllRelations() { return List.copyOf(edges); }

    // ==================== 知识图谱根文档 ====================

    /**
     * 从文档中抽取实体和关系并入库。
     *
     * @param content 文档文本内容
     * @param docId   文档 ID
     * @param kbName  知识库名称
     */
    public void extractFromDocument(String content, String docId, String kbName) {
        if (content == null || content.isBlank()) return;
        if (!extractor.isAvailable()) {
            log.debug("[KnowledgeGraph] 抽取器不可用，跳过: docId={}", docId);
            return;
        }
        try {
            EntityExtractor.ExtractionResult result = extractor.extract(content, docId, kbName);
            if (result.isEmpty()) return;
            addNodes(result.entities());
            addRelations(result.relations());
            log.info("[KnowledgeGraph] 实体关系抽取完成: docId={}, entities={}, relations={}",
                    docId, result.entities().size(), result.relations().size());
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] 实体关系抽取异常: docId={}, error={}", docId, e.getMessage());
        }
    }

    /** 清空图谱数据 */
    public void clear() {
        nodes.clear();
        edges.clear();
    }

    public int nodeCount() { return nodes.size(); }
    public int edgeCount() { return edges.size(); }
}
