/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 警告/废弃提示。
 * <p>当下游服务发生变更（如 Agent 升级、模型废弃）时，用于通知调用方。</p>
 *
 * <pre>
 * {
 *   "code": "DEPRECATED_MODEL",
 *   "message": "模型 gpt-3.5-turbo 将于 2026-09-01 下线，请迁移至 gpt-4o-mini"
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"code", "message"})
public class AgentWarning {

    /** 警告码（如 DEPRECATED_MODEL, RATE_LIMIT_WARNING） */
    private String code;

    /** 警告描述 */
    private String message;

    public AgentWarning() {}

    public AgentWarning(String code, String message) {
        this.code = code;
        this.message = message;
    }

    // ---- Getters / Setters ----

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
