/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.error;

/**
 * 带标准错误码的运行时异常——是表驱动恢复的数据载体。
 * <p>
 * 当系统检测到可识别的错误时，抛此异常替代 {@code RuntimeException} 或纯文本日志，
 * 上游 {@link ErrorRecoveryService} 可通过 {@link #getErrorCode()} 定位恢复策略。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   // 工具层抛异常
 *   throw new AgentException(AgentErrorCode.ORDER_NOT_FOUND,
 *           "未找到订单 ORD-2024001");
 *
 *   // 上游 catch 后恢复
 *   } catch (AgentException e) {
 *       RecoveryAction action = recoveryService.resolve(e.getErrorCode());
 *       // ...
 *   }
 * }</pre>
 *
 * @see AgentErrorCode
 * @see ErrorRecoveryService
 */
public class AgentException extends RuntimeException {

    private final AgentErrorCode errorCode;

    /**
     * @param errorCode 标准错误码（不可为 null）
     * @param message   详细描述
     */
    public AgentException(AgentErrorCode errorCode, String message) {
        super(message);
        if (errorCode == null) {
            throw new IllegalArgumentException("AgentErrorCode must not be null");
        }
        this.errorCode = errorCode;
    }

    /**
     * @param errorCode 标准错误码
     * @param message   详细描述
     * @param cause     原始异常链
     */
    public AgentException(AgentErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        if (errorCode == null) {
            throw new IllegalArgumentException("AgentErrorCode must not be null");
        }
        this.errorCode = errorCode;
    }

    public AgentErrorCode getErrorCode() {
        return errorCode;
    }

    /** 获取标准化的 ToolResult JSON 字符串 */
    public String toToolResultJson() {
        return com.example.smartassistant.common.tool.ToolResult.error(
                errorCode.getCode(),
                getMessage(),
                errorCode.isRetryable(),
                errorCode.getDefaultHint());
    }

    @Override
    public String getMessage() {
        String msg = super.getMessage();
        return msg != null ? msg : errorCode.getDefaultHint();
    }
}
