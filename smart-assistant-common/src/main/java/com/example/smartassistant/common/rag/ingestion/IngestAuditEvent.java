/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion;

/**
 * 入库操作审计事件（P0，2026-07-07）。
 * <p>
 * 记录每次摄取 / 隔离 / 回滚操作的主体责任与结果，满足字节 RAG 七连问第七问
 * “错误文档入库怎么办”的可回溯要求：谁、何时、对哪个文档、做了什么。
 * </p>
 *
 * @param operator   操作主体（租户 ID 或 "system"）
 * @param action     动作类型：INGEST / SUPERSEDE / QUARANTINE / RESTORE
 * @param docId      文档 ID（动作级别事件可为空）
 * @param baseDocId  基础文档 ID（用于版本聚合）
 * @param version    文档版本
 * @param timestamp  事件时间（毫秒）
 * @param detail     附加说明（如 superseded 数量、keep 文档 ID）
 */
public record IngestAuditEvent(
        String operator,
        String action,
        String docId,
        String baseDocId,
        String version,
        long timestamp,
        String detail) {

    public static IngestAuditEvent of(String operator, String action,
                                       String baseDocId, String version, String detail) {
        return new IngestAuditEvent(operator, action, "", baseDocId, version,
                System.currentTimeMillis(), detail);
    }
}
