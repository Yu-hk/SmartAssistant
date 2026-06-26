package com.example.smartassistant.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 统一 API 响应封装。
 * <p>
 * 所有 Controller 对外接口均通过此类返回，确保响应格式统一。
 * 成功时使用 {@link #success(Object)}，失败时使用 {@link #error(int, String)} 或 {@link #error(int, String, ErrorDetail)}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    /** 成功业务码 */
    public static final int CODE_SUCCESS = 0;

    /** 业务状态码：0 表示成功，非零表示各类错误 */
    private int code;

    /** 对人类可读的状态描述 */
    private String message;

    /** 请求追踪 ID */
    private String requestId;

    /** 服务端时间戳（毫秒） */
    private long timestamp;

    /** 实际业务数据，错误时可为 null */
    private T data;

    /** 错误明细（仅错误时存在） */
    private ErrorDetail error;

    // ==================== 工厂方法 ====================

    /** 成功响应 */
    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.code = CODE_SUCCESS;
        resp.message = "success";
        resp.requestId = generateRequestId();
        resp.timestamp = Instant.now().toEpochMilli();
        resp.data = data;
        return resp;
    }

    /** 仅带消息的成功响应（data = null） */
    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    /** 错误响应（带 error detail） */
    public static <T> ApiResponse<T> error(int code, String message, ErrorDetail error) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.code = code;
        resp.message = message;
        resp.requestId = generateRequestId();
        resp.timestamp = Instant.now().toEpochMilli();
        resp.error = error;
        return resp;
    }

    /** 错误响应（无 error detail） */
    public static <T> ApiResponse<T> error(int code, String message) {
        return error(code, message, null);
    }

    // ==================== 内部类型 ====================

    /** 错误明细 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorDetail {

        /** 错误分类：validation_error / auth_error / rate_limit / agent_timeout / tool_error / internal_error */
        private String type;

        /** 详细错误描述 */
        private String detail;

        /** 字段级校验错误（输入校验场景） */
        private Map<String, String> fields;

        /** 链路追踪 ID */
        private String traceId;
    }

    // ==================== 工具方法 ====================

    private static String generateRequestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
