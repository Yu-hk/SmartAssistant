/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * 符合 RFC 9457 (Problem Details for HTTP APIs) 的错误详情对象。
 * <p>取代 {@link AgentApiResponse} 中原有的 {@code errorCode: String} 单字符串字段，
 * 提供结构化的错误信息，支持字段级错误明细。</p>
 *
 * <pre>
 * {
 *   "code": "VALIDATION_ERROR",
 *   "title": "请求参数校验失败",
 *   "detail": "userId 不能为空",
 *   "status": 422,
 *   "instance": "/api/math/chat",
 *   "details": [
 *     { "field": "userId", "message": "不能为空", "rejectedValue": null }
 *   ]
 * }
 * </pre>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"code", "title", "detail", "status", "instance", "details"})
public class AgentError {

    /** 错误码（如 VALIDATION_ERROR, INTERNAL_ERROR），与 {@code AgentApiResponses.ERROR_*} 常量对应 */
    private String code;

    /** 简短的可读摘要（稳定不变，不因每次出现而变化） */
    private String title;

    /** 本次出现的具体错误描述（RFC 9457: occurrence-specific explanation） */
    private String detail;

    /** HTTP 状态码（如 400, 422, 500） */
    private int status;

    /** 本次出现的具体 URI 路径（用于日志关联），对应 RFC 9457 的 {@code instance} */
    private String instance;

    /** 字段级错误明细（用于参数校验等场景） */
    private List<ErrorDetail> details;

    public AgentError() {}

    private AgentError(Builder builder) {
        this.code = builder.code;
        this.title = builder.title;
        this.detail = builder.detail;
        this.status = builder.status;
        this.instance = builder.instance;
        this.details = builder.details;
    }

    // ---- Builder ----

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String code;
        private String title;
        private String detail;
        private int status;
        private String instance;
        private List<ErrorDetail> details;

        Builder() {}

        public Builder code(String code) { this.code = code; return this; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder detail(String detail) { this.detail = detail; return this; }
        public Builder status(int status) { this.status = status; return this; }
        public Builder instance(String instance) { this.instance = instance; return this; }
        public Builder details(List<ErrorDetail> details) { this.details = details; return this; }

        public AgentError build() { return new AgentError(this); }
    }

    // ---- Getters / Setters ----

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public String getInstance() { return instance; }
    public void setInstance(String instance) { this.instance = instance; }

    public List<ErrorDetail> getDetails() { return details; }
    public void setDetails(List<ErrorDetail> details) { this.details = details; }

    // ==================== 嵌套类：字段级错误明细 ====================

    /**
     * 字段级错误明细，对应 RFC 9457 的 {@code details[]}。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorDetail {

        /** 出错的字段名 */
        private String field;

        /** 错误描述 */
        private String message;

        /** 被拒绝的值 */
        private Object rejectedValue;

        public ErrorDetail() {}

        public ErrorDetail(String field, String message, Object rejectedValue) {
            this.field = field;
            this.message = message;
            this.rejectedValue = rejectedValue;
        }

        // ---- Getters / Setters ----

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public Object getRejectedValue() { return rejectedValue; }
        public void setRejectedValue(Object rejectedValue) { this.rejectedValue = rejectedValue; }
    }
}
