/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag;

/**
 * 文档版本元信息（P3-2 版本治理面板用值对象）。
 * <p>从 {@link KnowledgeDocument} 投影出版本治理所需的轻量字段，
 * 不含正文与向量，便于版本历史列表展示与回滚决策。</p>
 *
 * @param docId          文档 ID（含版本/块后缀，唯一）
 * @param baseDocId      基础文档 ID（去除版本与块后缀）
 * @param version        版本号（如 "v2"）
 * @param status         文档状态（ACTIVE / SUPERSEDED / QUARANTINED）
 * @param sourceUrl      来源 URL（回链）
 * @param ingestBatchId  摄入批次 ID（同批次 chunk 共享）
 * @param indexVersion   索引版本（构建向量时的策略/模型版本）
 * @param createdAt      入库时间戳（毫秒）
 * @param rawChecksum    原始文件/内容校验和
 */
public record DocumentVersionMeta(
        String docId,
        String baseDocId,
        String version,
        DocumentStatus status,
        String sourceUrl,
        String ingestBatchId,
        String indexVersion,
        long createdAt,
        String rawChecksum) {

    /** 从知识文档投影为版本元信息 */
    public static DocumentVersionMeta from(KnowledgeDocument d) {
        return new DocumentVersionMeta(
                d.getId(),
                d.getBaseDocId(),
                d.getVersion(),
                d.getDocumentStatus(),
                d.getSourceUrl(),
                d.getIngestBatchId(),
                d.getIndexVersion(),
                d.getCreatedAt(),
                d.getRawChecksum());
    }
}
