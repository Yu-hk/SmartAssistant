/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion;

import java.util.Collections;
import java.util.List;

/**
 * 知识摄入结果——封装摄入操作的统计信息和错误列表。
 *
 * @param docCount   摄入的文档数
 * @param elapsedMs  耗时（毫秒）
 * @param errors     错误列表（为空表示成功）
 */
public record IngestionResult(
        int docCount,
        long elapsedMs,
        List<String> errors,
        String ingestBatchId,
        boolean pendingApproval
) {

    /** 向后兼容：3 参构造（无批次/待审信息） */
    public IngestionResult(int docCount, long elapsedMs, List<String> errors) {
        this(docCount, elapsedMs, errors, "", false);
    }

    /** 是否成功（无错误、文档数 > 0、且非待审批挂起） */
    public boolean isSuccess() {
        return errors.isEmpty() && docCount > 0 && !pendingApproval;
    }

    /** 是否待审批挂起（已隔离入库，等待运营审批后激活） */
    public boolean isHeld() {
        return pendingApproval;
    }

    /** 是否部分成功（有文档但存在错误） */
    public boolean isPartial() {
        return docCount > 0 && !errors.isEmpty();
    }

    /** 是否完全失败 */
    public boolean isFailed() {
        return docCount == 0 && !errors.isEmpty();
    }

    /** 是否跳过（变更检测命中，文档未变更） */
    public boolean isSkipped() {
        return docCount == 0 && errors.isEmpty() && elapsedMs > 0;
    }

    /** 空结果（解析为空） */
    public static IngestionResult empty(String reason) {
        return new IngestionResult(0, 0, List.of(reason));
    }

    /** 失败结果 */
    public static IngestionResult failed(String error) {
        return new IngestionResult(0, 0, List.of(error));
    }

    /** 成功结果 */
    public static IngestionResult success(int docCount, long elapsedMs) {
        return new IngestionResult(docCount, elapsedMs, Collections.emptyList());
    }

    /**
     * 跳过结果（变更检测命中，文档未变更）。
     *
     * @param reason    跳过原因
     * @param docCount  跳过的文档数
     * @param elapsedMs 检测耗时
     */
    public static IngestionResult skipped(String reason, int docCount, long elapsedMs) {
        return new IngestionResult(docCount, elapsedMs, List.of("SKIPPED: " + reason));
    }

    /**
     * 待审批挂起结果（已隔离入库，等待运营审批后激活）。
     *
     * @param docCount      隔离入库的文档数
     * @param elapsedMs     耗时（毫秒）
     * @param ingestBatchId 摄入批次 ID（供审批 API 定位批次）
     */
    public static IngestionResult held(int docCount, long elapsedMs, String ingestBatchId) {
        return new IngestionResult(docCount, elapsedMs, Collections.emptyList(), ingestBatchId, true);
    }

    @Override
    public String toString() {
        if (isSkipped()) {
            return "IngestionResult{跳过, 耗时=" + elapsedMs + "ms}";
        } else if (isSuccess()) {
            return "IngestionResult{成功, docs=" + docCount + ", 耗时=" + elapsedMs + "ms}";
        } else if (isPartial()) {
            return "IngestionResult{部分成功, docs=" + docCount + ", errors=" + errors + "}";
        } else {
            return "IngestionResult{失败, errors=" + errors + "}";
        }
    }
}
