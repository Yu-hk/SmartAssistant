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
 * 速率限制信息。
 * <p>对应 OpenAI Response Headers 中的 {@code x-ratelimit-*} 系列字段，
 * 用于告知调用方当前速率限制状态。</p>
 *
 * <pre>
 * {
 *   "remaining": 58,
 *   "limit": 100,
 *   "resetTimestamp": 1718467800000,
 *   "resetSeconds": 42
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"remaining", "limit", "resetTimestamp", "resetSeconds"})
public class RateLimitInfo {

    /** 当前剩余可用请求数 */
    private int remaining;

    /** 周期内总限制数 */
    private int limit;

    /** 重置时间戳（毫秒） */
    private Long resetTimestamp;

    /** 距离重置的秒数（给前端倒计时用） */
    private Integer resetSeconds;

    public RateLimitInfo() {}

    public RateLimitInfo(int remaining, int limit, Long resetTimestamp, Integer resetSeconds) {
        this.remaining = remaining;
        this.limit = limit;
        this.resetTimestamp = resetTimestamp;
        this.resetSeconds = resetSeconds;
    }

    // ---- Getters / Setters ----

    public int getRemaining() { return remaining; }
    public void setRemaining(int remaining) { this.remaining = remaining; }

    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }

    public Long getResetTimestamp() { return resetTimestamp; }
    public void setResetTimestamp(Long resetTimestamp) { this.resetTimestamp = resetTimestamp; }

    public Integer getResetSeconds() { return resetSeconds; }
    public void setResetSeconds(Integer resetSeconds) { this.resetSeconds = resetSeconds; }
}
