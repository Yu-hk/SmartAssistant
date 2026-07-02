/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

import java.util.List;

/**
 * 知识库接口——支持向量检索的知识库抽象。
 * <p>
 * 支持增删文档、BGE 向量嵌入、余弦相似度搜索、两级检索（粗筛+精排）。
 * 所有检索都支持可选的租户隔离（tenantId），权限过滤在检索前完成。
 * </p>
 */
public interface KnowledgeBase {

    /** 默认公开租户 ID */
    String PUBLIC_TENANT = "";

    /** 知识库名称 */
    String getName();

    /** 添加文档（自动计算 embedding） */
    void addDocument(KnowledgeDocument doc);

    /** 批量添加文档 */
    default void addDocuments(List<KnowledgeDocument> docs) {
        docs.forEach(this::addDocument);
    }

    /** 按 ID 删除文档 */
    void removeDocument(String id);

    /**
     * ⭐ 按基础文档 ID 删除所有关联的 chunk（先删后增）。
     * <p>
     * 文档修改时，先调用此方法删除旧版本的所有 chunk，
     * 再调用 {@link #addDocument(KnowledgeDocument)} 写入新版本。
     * 避免旧 chunk 残留导致检索混入过期结果。
     * </p>
     * <p>
     * 默认实现是 no-op，子类应覆盖提供物理删除逻辑。
     * </p>
     *
     * @param baseDocId 基础文档 ID（去除版本后缀，如 "ORD-REFUND-001"）
     */
    default void removeByBaseDocId(String baseDocId) {
        // 默认 no-op，子类按需覆盖
    }

    /**
     * 检索最相关的文档（不限租户）。
     *
     * @param query  检索查询（自然语言）
     * @param topK   返回条数
     * @return 按相关度降序排列的结果
     */
    default List<KnowledgeHit> search(String query, int topK) {
        return search(query, topK, PUBLIC_TENANT);
    }

    /**
     * 检索最相关的文档（按租户隔离）。
     * <p>
     * 权限过滤在检索前完成：仅返回 tenantId 为空（公开）或与请求 tenantId 匹配的文档。
     * 参考 RAG 文章的生产原则：权限过滤必须在检索前完成，
     * 如果先召回高权限内容再让模型"不要说出来"，这不是安全，是自欺欺人。
     * </p>
     *
     * @param query    检索查询（自然语言）
     * @param topK     返回条数
     * @param tenantId 租户 ID（空字符串表示查询公开文档）
     * @return 按相关度降序排列的结果
     */
    List<KnowledgeHit> search(String query, int topK, String tenantId);

    /** 获取文档总数 */
    int size();

    /** 重新计算所有文档的 embedding（知识库变更后调用） */
    void reindex();
}
