package com.example.smartassistant.common.exception;

import lombok.Getter;

/**
 * 业务异常，统一由 GlobalExceptionHandler 处理。
 * <p>
 * 所有 Service 层或 Controller 层需要抛出的业务/系统异常均使用此类，
 * 避免各模块自定义异常类型。
 */
@Getter
public class ServiceException extends RuntimeException {

    /** HTTP 状态码 */
    private final int httpStatus;

    /** 业务错误码（如 VALIDATION_ERROR, AGENT_TIMEOUT） */
    private final String errorCode;

    /** 错误详情（可选，将填入 ApiResponse.ErrorDetail.detail） */
    private final String detail;

    public ServiceException(int httpStatus, String errorCode, String message) {
        this(httpStatus, errorCode, message, null);
    }

    public ServiceException(int httpStatus, String errorCode, String message, String detail) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.detail = detail;
    }

    /** 快速创建参数校验异常 (400) */
    public static ServiceException badRequest(String message) {
        return new ServiceException(400, "VALIDATION_ERROR", message);
    }

    /** 快速创建认证异常 (401) */
    public static ServiceException unauthorized(String message) {
        return new ServiceException(401, "AUTH_ERROR", message);
    }

    /** 快速创建资源未找到异常 (404) */
    public static ServiceException notFound(String message) {
        return new ServiceException(404, "NOT_FOUND", message);
    }

    /** 快速创建服务内部异常 (500) */
    public static ServiceException internal(String message) {
        return new ServiceException(500, "INTERNAL_ERROR", message);
    }
}
