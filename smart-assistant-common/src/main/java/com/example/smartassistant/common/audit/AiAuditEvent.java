/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.audit;

import java.time.Instant;

/**
 * AI 调用审计事件 — 记录每次生产环境 LLM 调用的完整链路信息。
 *
 * <p>由 {@code TokenUsageAdvisor}（调用成功/失败）与 {@code SafeGuardAdvisor}（调用被拦截）发布，
 * 经 {@code ApplicationEventPublisher} 派发，由 {@code AiAuditStore} 落库，
 * 补齐"每次生产 AI 调用"的可观测闭环（此前仅有分散日志 + 离线评测报告）。</p>
 *
 * <p>字段设计对标 Spring AI 2.0 工程化样本中的 {@code AiAuditEvent}：
 * 模型 / token 用量 / 耗时 / 用户输入摘要 / 结果类型，全部结构化、可检索。</p>
 *
 * @param traceId          请求链路 ID（来自 Slf4j MDC requestId/traceId），用于串联整条调用链
 * @param tenantId         租户 ID（来自 MDC tenantId，缺失时为 "-"）
 * @param provider         模型供应商（由 model 名推断：deepseek / alibaba / openai ...）
 * @param model            模型名（来自 ChatResponseMetadata.getModel()）
 * @param promptTokens     输入 token 数（Usage.getPromptTokens()）
 * @param completionTokens 输出 token 数（Usage.getCompletionTokens()）
 * @param totalTokens      总 token 数（Usage.getTotalTokens()）
 * @param latencyMs        LLM 调用耗时（毫秒）
 * @param resultType       结果类型：SUCCESS / ERROR / BLOCKED
 * @param approved         是否经过人工审批（HITL 场景为 true，普通调用为 false）
 * @param userInputDigest  用户输入摘要（截断至 200 字符，便于审计回溯，不落全量原文）
 * @param timestamp        事件产生时间
 */
public record AiAuditEvent(
        String traceId,
        String tenantId,
        String provider,
        String model,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        long latencyMs,
        String resultType,
        boolean approved,
        String userInputDigest,
        Instant timestamp) {
}
