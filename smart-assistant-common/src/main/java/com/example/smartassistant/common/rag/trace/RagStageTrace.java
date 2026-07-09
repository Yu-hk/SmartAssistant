/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.trace;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 全阶段 trace —— 以真实请求 {@code requestId} 为键，聚合一次请求的各阶段跨度。
 * <p>
 * 结构：{@code requestId → query → [RETRIEVAL span, GENERATION span] + 拒答标记}。
 * 存入 Redis {@code a2a:stage:trace:{requestId}}（见 {@link StageTraceRecorder}），
 * 供线上把"答非所问 / 无依据"等质量问题定位到具体阶段。
 * </p>
 *
 * <p>Jackson 可序列化：提供全参 {@link JsonCreator} 构造器以支持 Redis 读回。</p>
 */
public class RagStageTrace {

    /** 请求 ID（与 Consumer → Router → Agent 全链路一致） */
    private final String requestId;

    /** 原始用户问题 */
    private final String query;

    /** 处理该请求的 Agent 名称（如 order_agent / product_agent） */
    private final String agentName;

    /** 各阶段跨度（按添加顺序） */
    private final List<StageSpan> stages;

    /** 结构化拒答原因（无证据拒答时设置） */
    private String rejectionCode;

    /** 给用户的结构化拒答消息 */
    private String rejectionMessage;

    /** 创建时间戳 */
    private final long createdAt;

    @JsonCreator
    public RagStageTrace(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("query") String query,
            @JsonProperty("agentName") String agentName,
            @JsonProperty("stages") List<StageSpan> stages,
            @JsonProperty("rejectionCode") String rejectionCode,
            @JsonProperty("rejectionMessage") String rejectionMessage,
            @JsonProperty("createdAt") long createdAt) {
        this.requestId = requestId;
        this.query = query;
        this.agentName = agentName;
        this.stages = stages != null ? new ArrayList<>(stages) : new ArrayList<>();
        this.rejectionCode = rejectionCode;
        this.rejectionMessage = rejectionMessage;
        this.createdAt = createdAt;
    }

    /** 便捷构造：仅含基本标识，跨度与拒答信息后续通过方法追加 */
    public RagStageTrace(String requestId, String query, String agentName) {
        this(requestId, query, agentName, new ArrayList<>(), null, null, System.currentTimeMillis());
    }

    // ==================== 构建方法 ====================

    public RagStageTrace addStage(StageSpan span) {
        if (span != null) this.stages.add(span);
        return this;
    }

    public RagStageTrace markRejection(String code, String message) {
        this.rejectionCode = code;
        this.rejectionMessage = message;
        // 拒答本身占一个 REJECTION 阶段标记，便于前端/监控识别
        this.stages.add(StageSpan.of(RagStage.REJECTION, 0, StageSpan.STATUS_REJECTED,
                java.util.Map.of("code", code == null ? "" : code)));
        return this;
    }

    // ==================== 查询方法 ====================

    public String getRequestId() { return requestId; }
    public String getQuery() { return query; }
    public String getAgentName() { return agentName; }
    public List<StageSpan> getStages() { return stages; }
    public String getRejectionCode() { return rejectionCode; }
    public String getRejectionMessage() { return rejectionMessage; }
    /** 派生状态：有拒答码即视为拒答。不参与 JSON 序列化（由 rejectionCode 推导）。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isRejected() { return rejectionCode != null; }
    public long getCreatedAt() { return createdAt; }

    /** 取某阶段最后一个跨度（如 GENERATION 的耗时） */
    public StageSpan lastStageOf(RagStage stage) {
        for (int i = stages.size() - 1; i >= 0; i--) {
            if (stages.get(i).stage() == stage) return stages.get(i);
        }
        return null;
    }

    // ==================== 汇总 ====================

    public String toSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("StageTrace[requestId=").append(requestId)
          .append(", agent=").append(agentName == null ? "?" : agentName)
          .append(", query='").append(truncate(query, 40)).append("'");
        if (isRejected()) {
            sb.append(", REJECTED=").append(rejectionCode);
        }
        sb.append(", stages=").append(stages.size()).append("]");
        for (StageSpan s : stages) {
            sb.append("\n  - ").append(s.stage())
              .append(" ").append(s.status())
              .append(" ").append(s.durationMs()).append("ms");
            if (!s.metrics().isEmpty()) {
                sb.append(" ").append(s.metrics());
            }
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
