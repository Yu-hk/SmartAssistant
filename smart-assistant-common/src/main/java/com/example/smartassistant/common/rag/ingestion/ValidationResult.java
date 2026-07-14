/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.ingestion;

/**
 * 脏数据校验结果（REQ-1 拦截分支）。
 * <p>
 * {@code passed=true} 表示通过；{@code passed=false} 携带判废 {@code code} 与 {@code reason}，
 * 由 {@link ReviewQueueService} 入复核队列。
 * </p>
 */
public class ValidationResult {

    private final boolean passed;
    private final String code;
    private final String reason;

    private ValidationResult(boolean passed, String code, String reason) {
        this.passed = passed;
        this.code = code;
        this.reason = reason;
    }

    public static ValidationResult pass() {
        return new ValidationResult(true, "PASS", "");
    }

    public static ValidationResult reject(String code, String reason) {
        return new ValidationResult(false, code, reason);
    }

    public boolean isPassed() { return passed; }

    public String getCode() { return code; }

    public String getReason() { return reason; }

    @Override
    public String toString() {
        return passed ? "ValidationResult(PASS)" : "ValidationResult(REJECT," + code + "," + reason + ")";
    }
}
