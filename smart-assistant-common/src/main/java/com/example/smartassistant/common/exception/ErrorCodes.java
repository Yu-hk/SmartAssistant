package com.example.smartassistant.common.exception;

/**
 * 统一错误码常量。
 * <p>
 * 所有模块共享通用的错误码分类，各模块可在其后附加具体含义。
 */
public final class ErrorCodes {

    private ErrorCodes() { }

    // ==================== 通用错误 ====================

    /** 参数校验失败 */
    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    /** 请求参数绑定失败 */
    public static final String BIND_ERROR = "BIND_ERROR";
    /** 非法参数 */
    public static final String ILLEGAL_ARGUMENT = "ILLEGAL_ARGUMENT";
    /** 未认证 */
    public static final String AUTH_ERROR = "AUTH_ERROR";
    /** 权限不足 */
    public static final String FORBIDDEN = "FORBIDDEN";
    /** 资源不存在 */
    public static final String NOT_FOUND = "NOT_FOUND";
    /** 请求频率超限 */
    public static final String RATE_LIMIT = "RATE_LIMIT";
    /** 服务内部错误 */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    // ==================== Agent 专属错误 ====================

    /** Agent 未找到 */
    public static final String AGENT_NOT_FOUND = "AGENT_NOT_FOUND";
    /** Agent 执行超时 */
    public static final String AGENT_TIMEOUT = "AGENT_TIMEOUT";
    /** Agent 路由失败 */
    public static final String ROUTING_FAILED = "ROUTING_FAILED";
    /** 工具调用失败 */
    public static final String TOOL_CALL_FAILED = "TOOL_CALL_FAILED";
    /** LLM 服务异常 */
    public static final String LLM_SERVICE_ERROR = "LLM_SERVICE_ERROR";
    /** 远程服务调用失败 */
    public static final String REMOTE_CALL_FAILED = "REMOTE_CALL_FAILED";
}
