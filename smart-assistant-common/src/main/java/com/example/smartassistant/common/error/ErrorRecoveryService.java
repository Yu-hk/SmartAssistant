/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/**
 * 表驱动恢复路由器——根据 {@link AgentErrorCode} 自动决定恢复策略。
 * <p>
 * 核心设计思想（参照 ThinkingAgent）：
 * <ol>
 *   <li>每个错误码关联一个默认的 {@link RecoveryAction}</li>
 *   <li>恢复策略按代价从低到高排列，避免过度恢复</li>
 *   <li>支持分类级覆盖规则（如所有 SERVICE 类错误可统一降级）</li>
 *   <li>恢复动作可带参数（重试次数/退避基数/恢复消息）</li>
 * </ol>
 * </p>
 *
 * <h3>恢复策略优先级</h3>
 * <pre>
 * RETRY (最低代价) → RETRY_BACKOFF → RETRY_ALTERNATIVE
 * → CLARIFY_USER → FALLBACK_AGENT → TERMINATE (最高代价)
 * </pre>
 *
 * <p>
 * 可作为 Spring Bean 注入（{@code @Autowired}），也可作为纯工具类直接实例化。
 * 提供默认静态实例 {@link #DEFAULT} 供非 Spring 环境使用。
 * </p>
 *
 * @see AgentErrorCode
 * @see RecoveryAction
 */
public class ErrorRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(ErrorRecoveryService.class);

    /** 默认全局实例，供非 Spring 环境直接使用（如 SmartReActAgent） */
    public static final ErrorRecoveryService DEFAULT = new ErrorRecoveryService();

    /**
     * 错误码 → 恢复动作映射表（核心驱动表）。
     * <p>
     * AgentErrorCode 枚举中已定义了默认恢复动作，此表提供覆盖能力。
     * 如果枚举默认值已满足需求，此表可保持为空。
     */
    private static final Map<AgentErrorCode, RecoveryAction> CUSTOM_RECOVERY_TABLE = new EnumMap<>(AgentErrorCode.class);

    /**
     * 分类级恢复动作覆盖。
     * 当某个错误码未在 CUSTOM_RECOVERY_TABLE 中时，按此分类表一级匹配。
     */
    private static final Map<ErrorCategory, RecoveryAction> CATEGORY_FALLBACK_TABLE = new EnumMap<>(ErrorCategory.class);

    static {
        // 分类级兜底：如果某个分类的多数错误需要统一动作，在此配置
        CATEGORY_FALLBACK_TABLE.put(ErrorCategory.SYSTEM, RecoveryAction.FALLBACK_AGENT);
        CATEGORY_FALLBACK_TABLE.put(ErrorCategory.SECURITY, RecoveryAction.TERMINATE);
    }

    /**
     * 解析错误码对应的恢复动作。
     * <p>
     * 查找顺序：
     * <ol>
     *   <li>优先查找 {@link #CUSTOM_RECOVERY_TABLE} 表</li>
     *   <li>回退到错误码枚举的默认动作 {@link AgentErrorCode#getDefaultAction()}</li>
     * </ol>
     * </p>
     */
    public RecoveryAction resolve(AgentErrorCode code) {
        if (code == null) return RecoveryAction.TERMINATE;

        // 1. 查自定义映射表
        RecoveryAction customAction = CUSTOM_RECOVERY_TABLE.get(code);
        if (customAction != null) return customAction;

        // 2. 查分类级兜底表
        RecoveryAction categoryAction = CATEGORY_FALLBACK_TABLE.get(code.getCategory());
        if (categoryAction != null) return categoryAction;

        // 3. 回退到枚举默认值
        return code.getDefaultAction();
    }

    /**
     * 根据错误码和当前重试次数判断是否应该继续重试。
     */
    public boolean shouldRetry(AgentErrorCode code, int attempt) {
        if (code == null || !code.isRetryable()) return false;
        RecoveryAction action = resolve(code);
        int maxAttempts;
        switch (action) {
            case RETRY:
                maxAttempts = 3;
                break;
            case RETRY_BACKOFF:
                maxAttempts = 3;
                break;
            case RETRY_ALTERNATIVE:
                maxAttempts = 2;
                break;
            default:
                maxAttempts = 1;
        }
        return attempt < maxAttempts;
    }

    /**
     * 获取重试等待时间（毫秒），按退避策略计算。
     */
    public long getRetryDelayMs(AgentErrorCode code, int attempt) {
        RecoveryAction action = resolve(code);
        switch (action) {
            case RETRY:
                return 200L;
            case RETRY_BACKOFF:
                return (long) (200 * Math.pow(2, attempt));
            case RETRY_ALTERNATIVE:
                return 500L;
            default:
                return 0L;
        }
    }

    /**
     * 根据错误码生成向用户返回的友好提示信息。
     *
     * @param code    错误码
     * @param details 原始错误描述（可为 null）
     * @return 面向用户的友好提示
     */
    public String resolveUserMessage(AgentErrorCode code, String details) {
        if (code == null) return "系统内部错误，请稍后重试。";

        RecoveryAction action = resolve(code);
        String hint = code.getDefaultHint();

        switch (action) {
            case RETRY:
            case RETRY_BACKOFF:
                return hint + "……";
            case RETRY_ALTERNATIVE:
                return hint + "，已为您整理当前可获取的最佳信息。";
            case CLARIFY_USER:
                if (details != null && !details.isBlank()) {
                    return details;
                }
                return hint + "，请检查后重新尝试。";
            case FALLBACK_AGENT:
                return hint + "，已为您切换到备用模式。";
            case TERMINATE:
                return hint;
            default:
                return hint;
        }
    }

    /**
     * 记录错误恢复的审计日志。
     */
    public void logRecovery(AgentErrorCode code, RecoveryAction action,
                            String context, int attempt) {
        if (code == null) return;
        log.warn("[Recovery] code={}, action={}, context={}, attempt={}/{}",
                code.getCode(), action, context, attempt,
                action == RecoveryAction.TERMINATE ? "-" : "3");
    }
}
