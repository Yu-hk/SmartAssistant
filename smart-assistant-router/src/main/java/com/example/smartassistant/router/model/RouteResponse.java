/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.model;

import com.example.smartassistant.common.audit.ToolUsageCache;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 路由响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RouteResponse {

    /** Single registered business Agent; absent for multi-Agent or built-in execution. */
    private String agentName;

    /** SINGLE_AGENT / MULTI_AGENT / BUILTIN / FALLBACK. */
    private RoutingResult.ExecutionMode executionMode;

    /** Registered business Agents that participated in this response. */
    @Builder.Default
    private List<String> participatingAgents = List.of();

    /** COMPLETED / AWAITING_APPROVAL / CLARIFICATION / DEGRADED / FAILED. */
    private RoutingResult.WorkflowStatus workflowStatus;

    /**
     * 处理结果
     */
    private String result;

    /**
     * 置信度 (0.0 - 1.0)
     */
    private Double confidence;

    /**
     * 路由方式（LLM_ROUTING / KEYWORD_ROUTING）
     */
    private String routingMethod;

    /**
     * 意图标签（用于用户画像意图分布统计）
     */
    private String intentTag;

    /**
     * 错误信息（如果有）
     */
    private String error;

    /**
     * 是否命中了语义缓存（直接从缓存返回，无 Agent 执行）
     */
    @Builder.Default
    private Boolean fromCache = false;

    /** Whether the response is collecting a required parameter instead of completing the request. */
    @Builder.Default
    private Boolean clarification = false;

    /** Measured token usage for the complete Router/Agent request chain. */
    private Long promptTokens;

    private Long completionTokens;

    private Long totalTokens;

    /** Complete, argument-free tool telemetry for this request chain. */
    private Boolean toolUsageComplete;

    private List<ToolUsageCache.ToolCall> toolCalls;
}
