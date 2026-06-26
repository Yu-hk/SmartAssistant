/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.error;

/**
 * 恢复动作——定义当 {@link AgentErrorCode} 发生时应该采取的恢复策略。
 * <p>
 * 由 {@link ErrorRecoveryService} 通过错误码→动作映射表驱动执行。
 * 恢复策略按代价从低到高排列：CLARIFY_USER 最低，TERMINATE 最高。
 * </p>
 */
public enum RecoveryAction {

    /** 原地重试当前操作（适用于临时性失败） */
    RETRY,

    /** 带指数退避的重试（适用于限流/过载场景） */
    RETRY_BACKOFF,

    /** 换一种方式重试（如切换工具或参数） */
    RETRY_ALTERNATIVE,

    /** 向用户澄清/引导（非自动恢复型错误，如数据未找到） */
    CLARIFY_USER,

    /** 降级到备用回复（切换到更简单的响应模式或预设文案） */
    FALLBACK_AGENT,

    /** 终止执行，返回友好错误信息（不可恢复的错误） */
    TERMINATE
}
