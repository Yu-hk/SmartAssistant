/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion.job;

/**
 * 摄取任务状态机——对标生产级 RAG「异步可追踪数据链路」。
 *
 * <p>状态流转：{@code UPLOADED → PARSING → CHUNKING → EMBEDDING → INDEXED}；
 * 任意阶段异常进入 {@code FAILED}；重试经 {@code RETRYING} 短暂态后回到执行链。</p>
 *
 * <p>进度百分比随状态单调递增，便于前端轮询展示；{@code FAILED}/{@code RETRYING}
 * 不强制覆盖当前进度（保留失败前的最后进度）。</p>
 */
public enum IngestionJobStatus {

    UPLOADED(5, "已接收，等待处理"),
    PARSING(20, "文档解析中"),
    CHUNKING(45, "语义分块中"),
    EMBEDDING(80, "向量化与入库中"),
    INDEXED(100, "入库完成"),
    FAILED(0, "失败"),
    RETRYING(0, "重试中");

    private final int progress;
    private final String label;

    IngestionJobStatus(int progress, String label) {
        this.progress = progress;
        this.label = label;
    }

    /** 该状态对应的默认进度百分比 */
    public int progress() {
        return progress;
    }

    /** 该状态的人类可读描述 */
    public String label() {
        return label;
    }

    /** 是否终态（成功或失败，不再变化） */
    public boolean isTerminal() {
        return this == INDEXED || this == FAILED;
    }

    /** 是否失败终态 */
    public boolean isFailed() {
        return this == FAILED;
    }

    /**
     * 从字符串解析状态（容错：空/非法值回退为 {@link #FAILED}）。
     */
    public static IngestionJobStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return FAILED;
        }
        try {
            return valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return FAILED;
        }
    }
}
