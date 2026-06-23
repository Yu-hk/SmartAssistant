/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.tool;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具返回值格式化工具类。
 * <p>
 * 为所有 @Tool 方法提供统一的成功/失败响应格式，
 * 使 LLM 能够通过结构化错误码判断错误类型和重试策略。
 * </p>
 *
 * <p>返回格式（JSON 字符串）：</p>
 * <ul>
 *   <li>成功：直接返回原始数据字符串</li>
 *   <li>错误：{@code {"error_code":"CODE","message":"描述","retryable":true/false,"hint":"建议"}}</li>
 * </ul>
 *
 * <p><b>改进</b>：改用 Jackson ObjectMapper 序列化 JSON，
 * 替代手写字符串拼接，消除控制字符注入风险。</p>
 */
public final class ToolResult {

    private static final Logger log = LoggerFactory.getLogger(ToolResult.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolResult() {}

    /**
     * 成功返回（原样透传）。
     *
     * @param data 返回数据
     * @return 原字符串
     */
    public static String success(String data) {
        return data;
    }

    /**
     * 结构化错误返回（无 hint）。
     *
     * @param errorCode 机器可读的错误码，如 {@code ORDER_NOT_FOUND}
     * @param message   人类可读的错误描述
     * @param retryable 是否可重试（true=临时故障，false=不可恢复）
     * @return JSON 格式的错误字符串
     */
    public static String error(String errorCode, String message, boolean retryable) {
        return buildErrorJson(errorCode, message, retryable, null);
    }

    /**
     * 结构化错误返回（含 hint）。
     *
     * @param errorCode 机器可读的错误码，如 {@code ORDER_NOT_FOUND}
     * @param message   人类可读的错误描述
     * @param retryable 是否可重试（true=临时故障，false=不可恢复）
     * @param hint      给 LLM 的操作建议
     * @return JSON 格式的错误字符串
     */
    public static String error(String errorCode, String message, boolean retryable, String hint) {
        return buildErrorJson(errorCode, message, retryable, hint);
    }

    /**
     * 使用 Jackson ObjectMapper 构建错误 JSON。
     * 替代手写字符串拼接，消除控制字符注入风险。
     */
    private static String buildErrorJson(String errorCode, String message, boolean retryable, String hint) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error_code", errorCode != null ? errorCode : "");
        map.put("message", message != null ? message : "");
        map.put("retryable", retryable);
        if (hint != null) {
            map.put("hint", hint);
        }
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("[ToolResult] JSON 序列化失败: {}", e.getMessage());
            // 降级：返回简单错误字符串（不应发生，ObjectMapper 序列化 Map 极少失败）
            return "{\"error_code\":\"SERIALIZATION_ERROR\",\"message\":\"错误信息序列化失败\",\"retryable\":false}";
        }
    }

    // ==================== AgentErrorCode 增强 ====================

    /**
     * 使用 {@link AgentErrorCode} 枚举生成结构化错误返回。
     * <p>
     * 比直接传字符串更安全，确保错误码与恢复策略一致。
     * 推荐新代码优先使用此方法。
     * </p>
     *
     * @param code    标准错误码枚举
     * @param message 详细的错误描述（可包含上下文信息）
     * @return JSON 格式的错误字符串
     */
    public static String error(AgentErrorCode code, String message) {
        if (code == null) {
            return error("UNKNOWN_CODE", "未知错误", false);
        }
        return buildErrorJson(code.getCode(), message, code.isRetryable(), code.getDefaultHint());
    }

    /**
     * 使用 {@link AgentErrorCode} 生成错误返回（覆写 hint）。
     *
     * @param code    标准错误码枚举
     * @param message 详细的错误描述
     * @param hint    覆写默认提示信息
     * @return JSON 格式的错误字符串
     */
    public static String error(AgentErrorCode code, String message, String hint) {
        if (code == null) {
            return error("UNKNOWN_CODE", "未知错误", false);
        }
        return buildErrorJson(code.getCode(), message, code.isRetryable(), hint);
    }
}
