package com.example.smartassistant.toolregistry.model;

import com.example.smartassistant.toolregistry.config.RequestIdHolder;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 通用 API 响应包装。
 * <p>
 * 自动注入当前请求的 {@code requestId}，支持全链路追踪。
 * </p>
 *
 * @param <T> 数据类型
 */
@Data
@AllArgsConstructor
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;
    private String requestId;

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.requestId = RequestIdHolder.get();
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(0, message, data);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
