/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.tool;

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
 */
public final class ToolResult {

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
        return "{\"error_code\":\"" + escape(errorCode)
                + "\",\"message\":\"" + escape(message)
                + "\",\"retryable\":" + retryable + "}";
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
        return "{\"error_code\":\"" + escape(errorCode)
                + "\",\"message\":\"" + escape(message)
                + "\",\"retryable\":" + retryable
                + ",\"hint\":\"" + escape(hint) + "\"}";
    }

    /** 转义 JSON 特殊字符 */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
