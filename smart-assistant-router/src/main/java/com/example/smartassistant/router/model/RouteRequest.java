/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.model;

/**
 * 路由请求（显式 getter/setter，避免 Lombok Maven 编译依赖问题）。
 */
public class RouteRequest {

    /** 用户 ID */
    private Long userId;

    /** 用户问题（已包含用户画像的完整 Prompt） */
    private String question;

    /** 会话 ID（可选） */
    private String sessionId;

    /** 是否启用 RAG 增强 */
    private Boolean enableRag = false;

    /** 请求 ID（用于 Redis 存储） */
    private String requestId;

    public RouteRequest() {}

    public RouteRequest(Long userId, String question, String sessionId, Boolean enableRag, String requestId) {
        this.userId = userId;
        this.question = question;
        this.sessionId = sessionId;
        this.enableRag = enableRag;
        this.requestId = requestId;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Boolean getEnableRag() { return enableRag; }
    public void setEnableRag(Boolean enableRag) { this.enableRag = enableRag; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public static RouteRequestBuilder builder() { return new RouteRequestBuilder(); }

    public static class RouteRequestBuilder {
        private Long userId;
        private String question;
        private String sessionId;
        private Boolean enableRag = false;
        private String requestId;

        RouteRequestBuilder() {}

        public RouteRequestBuilder userId(Long userId) { this.userId = userId; return this; }
        public RouteRequestBuilder question(String question) { this.question = question; return this; }
        public RouteRequestBuilder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
        public RouteRequestBuilder enableRag(Boolean enableRag) { this.enableRag = enableRag; return this; }
        public RouteRequestBuilder requestId(String requestId) { this.requestId = requestId; return this; }

        public RouteRequest build() {
            return new RouteRequest(userId, question, sessionId, enableRag, requestId);
        }
    }
}
