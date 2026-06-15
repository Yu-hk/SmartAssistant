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
 * 统一 Agent API 响应模板。
 * <p>所有 Agent 相关 REST 接口（同步调用）必须使用此格式返回。
 * 配合 {@link AgentApiResponses} 工厂方法使用。</p>
 *
 * <pre>
 * {
 *   "success": true,
 *   "message": "操作成功",
 *   "data": { ... },
 *   "usage": {
 *     "promptTokens": 142,
 *     "completionTokens": 67,
 *     "totalTokens": 209,
 *     "modelName": "deepseek-chat"
 *   },
 *   "modelName": "deepseek-chat",
 *   "rateLimit": {
 *     "remaining": 58,
 *     "limit": 100,
 *     "resetTimestamp": 1718467800000,
 *     "resetSeconds": 42
 *   },
 *   "warnings": [
 *     { "code": "DEPRECATED_MODEL", "message": "模型 xxx 将于 2026-09 下线" }
 *   ],
 *   "requestId": "abc123",
 *   "timestamp": 1718467200000,
 *   "durationMs": 1234,
 *   "agentName": "travel_agent",
 *   "error": null
 * }
 * </pre>
 *
 * @param <T> 业务数据类型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"success", "message", "data", "usage", "modelName", "rateLimit", "warnings",
        "requestId", "timestamp", "durationMs", "agentName", "error"})
public class AgentApiResponse<T> {

    private boolean success;
    private String message;
    private T data;
    private TokenUsage usage;
    private String modelName;
    private RateLimitInfo rateLimit;
    private List<AgentWarning> warnings;
    private String requestId;
    private long timestamp;
    private long durationMs;
    private String agentName;
    private AgentError error;

    // ---- 仅限内部和反序列化使用 ----
    AgentApiResponse() {}

    // ---- Builder 模式 ----

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static class Builder<T> {
        private boolean success;
        private String message;
        private T data;
        private TokenUsage usage;
        private String modelName;
        private RateLimitInfo rateLimit;
        private List<AgentWarning> warnings;
        private String requestId;
        private long timestamp;
        private long durationMs;
        private String agentName;
        private AgentError error;

        Builder() {}

        public Builder<T> success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder<T> message(String message) {
            this.message = message;
            return this;
        }

        public Builder<T> data(T data) {
            this.data = data;
            return this;
        }

        public Builder<T> usage(TokenUsage usage) {
            this.usage = usage;
            return this;
        }

        public Builder<T> modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder<T> rateLimit(RateLimitInfo rateLimit) {
            this.rateLimit = rateLimit;
            return this;
        }

        public Builder<T> warnings(List<AgentWarning> warnings) {
            this.warnings = warnings;
            return this;
        }

        public Builder<T> requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder<T> timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder<T> durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder<T> agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        public Builder<T> error(AgentError error) {
            this.error = error;
            return this;
        }

        public AgentApiResponse<T> build() {
            AgentApiResponse<T> r = new AgentApiResponse<>();
            r.success = this.success;
            r.message = this.message;
            r.data = this.data;
            r.usage = this.usage;
            r.modelName = this.modelName;
            r.rateLimit = this.rateLimit;
            r.warnings = this.warnings;
            r.requestId = this.requestId;
            r.timestamp = this.timestamp != 0 ? this.timestamp : System.currentTimeMillis();
            r.durationMs = this.durationMs;
            r.agentName = this.agentName;
            r.error = this.error;
            return r;
        }
    }

    // ---- Getters ----

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public T getData() { return data; }
    public TokenUsage getUsage() { return usage; }
    public String getModelName() { return modelName; }
    public RateLimitInfo getRateLimit() { return rateLimit; }
    public List<AgentWarning> getWarnings() { return warnings; }
    public String getRequestId() { return requestId; }
    public long getTimestamp() { return timestamp; }
    public long getDurationMs() { return durationMs; }
    public String getAgentName() { return agentName; }
    public AgentError getError() { return error; }
}
