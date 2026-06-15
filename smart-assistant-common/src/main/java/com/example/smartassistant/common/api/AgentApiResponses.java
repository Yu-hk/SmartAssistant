/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.api;

/**
 * {@link AgentApiResponse} 的工厂方法，统一成功/失败响应的构建方式。
 *
 * <pre>{@code
 * // 成功响应
 * return AgentApiResponses.ok(data, agentName, requestId, durationMs);
 *
 * // 错误响应（简单方式）
 * return AgentApiResponses.error("错误信息", "ERROR_CODE", requestId, durationMs);
 *
 * // 错误响应（REST 9457 结构化方式）
 * return AgentApiResponses.error(AgentError.builder()
 *         .code("VALIDATION_ERROR")
 *         .title("请求参数校验失败")
 *         .detail("userId 不能为空")
 *         .status(422)
 *         .build(), requestId, durationMs);
 * }</pre>
 */
public final class AgentApiResponses {

    private AgentApiResponses() {}

    private static final String DEFAULT_SUCCESS_MESSAGE = "操作成功";

    // ==================== 成功响应 ====================

    /**
     * 成功响应（带业务数据）。
     *
     * @param data       业务数据
     * @param agentName  处理 Agent 名称（可为 null）
     * @param requestId  请求追踪 ID（可为 null）
     * @param durationMs 处理耗时（毫秒，0 表示未知）
     * @param <T>        数据类型
     * @return 统一响应
     */
    public static <T> AgentApiResponse<T> ok(T data, String agentName, String requestId, long durationMs) {
        return AgentApiResponse.<T>builder()
                .success(true)
                .message(DEFAULT_SUCCESS_MESSAGE)
                .data(data)
                .agentName(agentName)
                .requestId(requestId)
                .durationMs(durationMs)
                .build();
    }

    /**
     * 成功响应（带业务数据 + Token 消耗统计）。
     *
     * @param data       业务数据
     * @param usage      Token 消耗
     * @param agentName  处理 Agent 名称（可为 null）
     * @param requestId  请求追踪 ID（可为 null）
     * @param durationMs 处理耗时（毫秒，0 表示未知）
     * @param <T>        数据类型
     * @return 统一响应
     */
    public static <T> AgentApiResponse<T> ok(T data, TokenUsage usage, String agentName,
                                              String requestId, long durationMs) {
        return AgentApiResponse.<T>builder()
                .success(true)
                .message(DEFAULT_SUCCESS_MESSAGE)
                .data(data)
                .usage(usage)
                .agentName(agentName)
                .requestId(requestId)
                .durationMs(durationMs)
                .build();
    }

    /** 无业务数据的成功响应。 */
    public static AgentApiResponse<Void> ok(String agentName, String requestId, long durationMs) {
        return ok(null, agentName, requestId, durationMs);
    }

    // ==================== 错误码常量 ====================

    /** 通用错误码（对应 {@link AgentError#code}） */
    public static final String ERROR_INTERNAL = "INTERNAL_ERROR";
    public static final String ERROR_VALIDATION = "VALIDATION_ERROR";
    public static final String ERROR_NOT_FOUND = "NOT_FOUND";
    public static final String ERROR_FORBIDDEN = "FORBIDDEN";
    public static final String ERROR_TIMEOUT = "TIMEOUT";
    public static final String ERROR_AGENT_FAILED = "AGENT_FAILED";
    public static final String ERROR_RATE_LIMITED = "RATE_LIMITED";

    // ==================== 错误响应 ====================

    /**
     * 错误响应（从 {@link AgentError} 对象构建）。
     * <p>推荐方式：符合 RFC 9457 标准，可携带字段级错误明细。</p>
     *
     * @param error      错误详情对象
     * @param requestId  请求追踪 ID
     * @param durationMs 处理耗时
     * @param <T>        数据类型
     * @return 统一响应（success=false, data=null）
     */
    public static <T> AgentApiResponse<T> error(AgentError error, String requestId, long durationMs) {
        return error(error, requestId, durationMs, null);
    }

    /**
     * 错误响应（从 {@link AgentError} 对象构建，含 agentName）。
     *
     * @param error      错误详情对象
     * @param requestId  请求追踪 ID
     * @param durationMs 处理耗时
     * @param agentName  处理 Agent 名称（可为 null）
     * @param <T>        数据类型
     * @return 统一响应（success=false, data=null）
     */
    public static <T> AgentApiResponse<T> error(AgentError error, String requestId,
                                                  long durationMs, String agentName) {
        String msg = error.getDetail() != null ? error.getDetail() : error.getTitle();
        return AgentApiResponse.<T>builder()
                .success(false)
                .message(msg)
                .error(error)
                .agentName(agentName)
                .requestId(requestId)
                .durationMs(durationMs)
                .build();
    }

    /**
     * 错误响应（从 {@link AgentError} 对象 + 自定义 message）。
     *
     * @param message    错误描述（覆盖 {@code error.detail}）
     * @param error      错误详情对象
     * @param requestId  请求追踪 ID
     * @param durationMs 处理耗时
     * @param <T>        数据类型
     * @return 统一响应（success=false, data=null）
     */
    public static <T> AgentApiResponse<T> error(String message, AgentError error,
                                                  String requestId, long durationMs) {
        return AgentApiResponse.<T>builder()
                .success(false)
                .message(message)
                .error(error)
                .requestId(requestId)
                .durationMs(durationMs)
                .build();
    }

    /**
     * 错误响应（简单方式，向后兼容）。
     * <p>自动构建 {@link AgentError} 对象。</p>
     *
     * @param message    错误描述
     * @param errorCode  错误码，使用 {@code ERROR_*} 常量
     * @param requestId  请求追踪 ID
     * @param durationMs 处理耗时
     * @param <T>        数据类型
     * @return 统一响应
     */
    public static <T> AgentApiResponse<T> error(String message, String errorCode,
                                                  String requestId, long durationMs) {
        AgentError error = AgentError.builder()
                .code(errorCode)
                .title(errorCode)
                .detail(message)
                .build();
        return error(error, requestId, durationMs);
    }

    /** 使用默认错误码的错误响应。 */
    public static <T> AgentApiResponse<T> error(String message, String requestId, long durationMs) {
        return error(message, ERROR_INTERNAL, requestId, durationMs);
    }

    /** 无 requestId 的简单错误响应（用于非 Agent 上下文）。 */
    public static <T> AgentApiResponse<T> error(String message, String errorCode) {
        return error(message, errorCode, null, 0);
    }
}
