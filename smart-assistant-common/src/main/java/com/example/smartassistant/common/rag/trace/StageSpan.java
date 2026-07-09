/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.trace;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 单个阶段的时间跨度记录（不可变）。
 *
 * <p>记录某次请求在 {@link RagStage} 上的耗时、状态与可观测指标，
 * 用于把质量问题（如"答非所问"）定位到 RETRIEVAL 还是 GENERATION 阶段。</p>
 *
 * @param stage    阶段（RETRIEVAL / GENERATION / REJECTION）
 * @param durationMs 该阶段耗时（毫秒）；被跳过时为 0
 * @param status   状态：OK / REJECTED / ERROR / SKIPPED
 * @param metrics  阶段可观测指标（如 qualityScore、hitCount、outputLength），可为空
 */
public record StageSpan(
        @JsonProperty("stage") RagStage stage,
        @JsonProperty("durationMs") long durationMs,
        @JsonProperty("status") String status,
        @JsonProperty("metrics") Map<String, Object> metrics
) {

    @JsonCreator
    public StageSpan {
        // 规范化：metrics 为 null 时置为空 Map，避免序列化 NPE
        metrics = metrics == null ? Map.of() : metrics;
    }


    /** 阶段状态常量 */
    public static final String STATUS_OK = "OK";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_SKIPPED = "SKIPPED";

    /**
     * 工厂方法（metrics 为 null 时自动置为空 Map，避免序列化 NPE）。
     */
    public static StageSpan of(RagStage stage, long durationMs, String status, Map<String, Object> metrics) {
        return new StageSpan(stage, durationMs, status, metrics == null ? Map.of() : metrics);
    }
}
