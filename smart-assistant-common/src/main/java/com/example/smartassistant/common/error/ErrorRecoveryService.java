package com.example.smartassistant.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 错误恢复服务。
 * <p>
 * 根据错误码和尝试次数决定恢复策略：是否重试、延迟多久、采用哪种方式恢复。
 * 使用表驱动方式管理策略映射，便于扩展和维护。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-06-29
 */
public class ErrorRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(ErrorRecoveryService.class);

    /** 默认实例（单例） */
    public static final ErrorRecoveryService DEFAULT = new ErrorRecoveryService();

    /** 最大重试次数 */
    private static final int MAX_RETRIES = 3;

    /** 基础重试延迟（毫秒） */
    private static final long BASE_DELAY_MS = 1000L;

    // ==================== 核心方法 ====================

    /**
     * 判断给定错误码在指定尝试次数后是否应该重试。
     *
     * @param errorCode 当前错误码
     * @param attempt   已尝试次数（从 1 开始）
     * @return 如果错误码可重试且未超过最大次数，返回 true
     */
    public boolean shouldRetry(AgentErrorCode errorCode, int attempt) {
        if (errorCode == null) {
            return false;
        }
        // 错误码本身不可重试 → 不重试
        if (!errorCode.isRetryable()) {
            return false;
        }
        // 超过最大重试次数 → 不重试
        if (attempt >= MAX_RETRIES) {
            return false;
        }
        return true;
    }

    /**
     * 获取当前重试轮次的延迟时间（毫秒）。
     * <p>
     * 采用指数退避策略：baseDelay * 2^attempt。
     * </p>
     *
     * @param errorCode 当前错误码（用于未来支持差异化延迟）
     * @param attempt   已尝试次数（从 0 开始）
     * @return 延迟毫秒数
     */
    public long getRetryDelayMs(AgentErrorCode errorCode, int attempt) {
        if (errorCode == null) {
            return BASE_DELAY_MS;
        }
        // 指数退避：base * 2^attempt
        return BASE_DELAY_MS * (1L << Math.min(attempt, 5));
    }

    /**
     * 根据错误码解析恢复策略。
     *
     * @param errorCode 当前错误码
     * @return 对应的恢复动作
     */
    public RecoveryAction resolve(AgentErrorCode errorCode) {
        if (errorCode == null) {
            return RecoveryAction.TERMINATE;
        }
        return switch (errorCode) {
            // 可重试错误 → RETRY_BACKOFF
            case AGENT_TIMEOUT,
                 AGENT_EMPTY_REPLY,
                 TOOL_EXECUTION_FAILED,
                 TOOL_EXECUTION_ERROR,
                 TOOL_IMAGE_GENERATION_FAILED,
                 TOOL_IMAGE_GENERATION_TIMEOUT,
                 VALIDATION_IMAGE_ANALYSIS,
                 SERVICE_NEWS_UNAVAILABLE,
                 SERVICE_SEARCH_UNAVAILABLE,
                 SERVICE_RATE_UNAVAILABLE,
                 SERVICE_WEATHER_UNAVAILABLE,
                 SERVICE_COUPON_QUERY_FAILED,
                 SERVICE_COUPON_CALC_FAILED,
                 UPDATE_FAILED,
                 SECURITY_SCRIPT_TIMEOUT ->
                    RecoveryAction.RETRY_BACKOFF;

            // 数据未找到 → CLARIFY_USER
            case DATA_NOT_FOUND,
                 ORDER_NOT_FOUND,
                 PRODUCT_NOT_FOUND,
                 LOGISTICS_NOT_FOUND,
                 WEATHER_NO_DATA,
                 NO_RESULTS ->
                    RecoveryAction.CLARIFY_USER;

            // 安全校验错误 → TERMINATE
            case SECURITY_SCRIPT_REJECTED,
                 SECURITY_SCRIPT_RESOURCE_LIMIT,
                 PERMISSION_DENIED ->
                    RecoveryAction.TERMINATE;

            // 其他不可重试错误 → CLARIFY_USER
            default -> RecoveryAction.CLARIFY_USER;
        };
    }

    /**
     * 记录恢复操作日志。
     *
     * @param errorCode 错误码
     * @param action    执行的恢复动作
     * @param detail    详细上下文
     * @param attempt   当前尝试次数
     */
    public void logRecovery(AgentErrorCode errorCode, RecoveryAction action,
                            String detail, int attempt) {
        log.warn("[ErrorRecovery] code={}, action={}, attempt={}, detail={}",
                errorCode != null ? errorCode.getCode() : "null",
                action, attempt, detail != null ? detail : "");
    }

    /**
     * 根据错误码和详细消息生成用户友好的提示。
     *
     * @param errorCode 错误码
     * @param detailMessage 详细错误消息（可能包含内部信息，需脱敏）
     * @return 用户友好的提示消息
     */
    public String resolveUserMessage(AgentErrorCode errorCode, String detailMessage) {
        if (errorCode == null) {
            return "系统暂时无法处理您的请求，请稍后再试";
        }
        // 使用枚举自带的 defaultHint
        return errorCode.getDefaultHint();
    }
}
