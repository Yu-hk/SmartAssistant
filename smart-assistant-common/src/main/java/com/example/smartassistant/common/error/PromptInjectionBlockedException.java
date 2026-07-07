/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.error;

/**
 * Prompt 注入被内容安全护栏拦截时抛出的异常。
 *
 * <p>由 {@code SafeGuardAdvisor} 在检测到越权注入指令时抛出；
 * {@code SmartReActAgent} 的执行循环会捕获该异常并返回友好的拦截提示，
 * 而非将其作为模型故障处理。同时 {@code SafeGuardAdvisor} 会发布
 * {@code resultType=BLOCKED} 的 {@code AiAuditEvent} 以供审计。</p>
 */
public class PromptInjectionBlockedException extends RuntimeException {

    public PromptInjectionBlockedException(String reason) {
        super(reason);
    }
}
