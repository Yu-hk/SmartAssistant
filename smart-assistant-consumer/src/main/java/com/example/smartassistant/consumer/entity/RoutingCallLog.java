/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 路由调用日志实体 (MyBatis Plus)
 * 记录每一次路由决策，用于问题排查和数据分析
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("routing_call_log")
public class RoutingCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 会话 ID
     */
    @TableField("session_id")
    private String sessionId;

    /**
     * Authenticated user that owns this conversation log. Null is reserved for
     * legacy/system rows and must never be exposed to ordinary users.
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 用户原始输入
     */
    @TableField("user_input")
    private String userInput;

    /**
     * 路由到的 Agent 名称
     */
    @TableField("routed_agent")
    private String routedAgent;

    /**
     * 路由方式: keyword_match / semantic / llm_fallback
     */
    @TableField("route_method")
    private String routeMethod;

    /**
     * 语义匹配分数
     */
    @TableField("match_score")
    private BigDecimal matchScore;

    /**
     * 命中的规则 ID
     */
    @TableField("matched_rule_id")
    private Long matchedRuleId;

    /**
     * LLM 实际接收到的 question（用于 debug）
     */
    @TableField("llm_received_question")
    private String llmReceivedQuestion;

    /**
     * 响应摘要（前500字符）
     */
    @TableField("response_summary")
    private String responseSummary;

    /**
     * 耗时（毫秒）
     */
    @TableField("latency_ms")
    private Long latencyMs;

    /** Provider-reported input tokens. Null means telemetry was not captured. */
    @TableField("prompt_tokens")
    private Long promptTokens;

    /** Provider-reported output tokens. Null means telemetry was not captured. */
    @TableField("completion_tokens")
    private Long completionTokens;

    /** Provider-reported total tokens. Zero is a valid measured value. */
    @TableField("total_tokens")
    private Long totalTokens;

    /** JSON tool telemetry wrapper; null denotes historical/uncollected data. */
    @TableField("tool_calls")
    private String toolCalls;

    /**
     * 状态: SUCCESS / FAILED / TIMEOUT
     */
    @TableField("status")
    @Builder.Default
    private String status = "SUCCESS";

    /**
     * 错误信息
     */
    @TableField("error_message")
    private String errorMessage;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
