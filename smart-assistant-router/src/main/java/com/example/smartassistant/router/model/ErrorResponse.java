/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 统一错误响应模型
 * <p>
 * 所有 Router API 的错误响应均使用此格式。
 * 包含错误码、服务标识、请求追踪号，方便排查问题。
 * <p>
 * <b>已废弃：</b>请使用 {@link com.example.smartassistant.common.response.ApiResponse} 替代。
 * 此 class 保留用于兼容旧调用方。所有新的异常处理已迁移至 {@code ApiResponse} 格式。
 *
 * @deprecated since v3.0 — 由 {@link com.example.smartassistant.common.response.ApiResponse} 统一替代
 */
@Deprecated
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    // ========== 固定字段 ==========

    /**
     * 错误码（如：ROUTER_001）
     */
    private String code;

    /**
     * 错误信息（用户友好）
     */
    private String msg;

    /**
     * HTTP 状态码
     */
    private int status;

    /**
     * 服务名称（用于日志区分）
     */
    private String service;

    /**
     * 时间戳
     */
    private LocalDateTime timestamp;

    // ========== 业务字段 ==========

    /**
     * 出错的 Agent 名称（如果是 Agent 调用错误）
     */
    private String agentName;

    /**
     * 路由方法（KEYWORD_ROUTING / DISCOVERY_ROUTING 等）
     */
    private String routingMethod;

    /**
     * 请求追踪 ID（用于关联日志）
     */
    private String traceId;

    /**
     * 详细错误信息（仅 DEBUG 模式展示）
     */
    private String detail;

    // ========== 快速构造方法 ==========

    public static ErrorResponse of(String code, String msg, int status) {
        return ErrorResponse.builder()
                .code(code)
                .msg(msg)
                .status(status)
                .service("router-service")
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static ErrorResponse of(String code, String msg, int status, String traceId) {
        return ErrorResponse.builder()
                .code(code)
                .msg(msg)
                .status(status)
                .service("router-service")
                .timestamp(LocalDateTime.now())
                .traceId(traceId)
                .build();
    }
}
