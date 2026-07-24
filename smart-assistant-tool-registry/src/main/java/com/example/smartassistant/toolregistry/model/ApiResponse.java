package com.example.smartassistant.toolregistry.model;

import com.example.smartassistant.toolregistry.config.RequestIdHolder;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 通用 API 响应包装。
 * <p>
 * 自动注入当前请求的 {@code requestId}，支持全链路追踪。
 * </p><p>
 * <b>⚠️ 与 common.ApiResponse 有意分叉（intentional divergence），请勿合并/替换。</b>
 * 差异点：① 工厂方法命名为 {@code ok/error}（common 为 {@code success/error}）；
 * ② 不含 {@code timestamp} 字段、不含 {@code error} 信封对象（common 用 ErrorDetail 承载错误明细）；
 * ③ {@code requestId} 取自 {@link com.example.smartassistant.toolregistry.config.RequestIdHolder}
 * （由 RequestIdFilter 注入并贯穿全链路），而非像 common 那样自生成 {@code req_xxx}。
 * 若改为复用 common.ApiResponse，会改变 JSON 契约并破坏 tool-registry 现有消费方与追踪语义，
 * 需配套做契约迁移（不在本次演进范围内）。
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
