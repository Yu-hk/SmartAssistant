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
 * <b>已废弃（零调用方）：</b>请使用 {@link com.example.smartassistant.common.response.ApiResponse} 替代。
 * 所有新代码已迁移至 {@code ApiResponse<T>}（JSON 字段 {@code "message"}，OpenAI 兼容格式）。
 * 此 class 保留仅用于序列化兼容；内部字段 {@code msg} 已重命名为 {@code message} 与 {@code ApiResponse} 对齐。
 *
 * @deprecated since v3.0 — 已由 {@link com.example.smartassistant.common.response.ApiResponse} 统一替代，零调用方
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
     * 错误信息（用户友好）——与 {@link com.example.smartassistant.common.response.ApiResponse} 的 {@code message} 字段对齐
     */
    private String message;

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

    public static ErrorResponse of(String code, String message, int status) {
        return ErrorResponse.builder()
                .code(code)
                .message(message)
                .status(status)
                .service("router-service")
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static ErrorResponse of(String code, String message, int status, String traceId) {
        return ErrorResponse.builder()
                .code(code)
                .message(message)
                .status(status)
                .service("router-service")
                .timestamp(LocalDateTime.now())
                .traceId(traceId)
                .build();
    }
}
