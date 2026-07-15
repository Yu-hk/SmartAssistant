package com.example.smartassistant.common.exception;

import com.example.smartassistant.common.response.ApiResponse;
import org.slf4j.MDC;
import org.springframework.web.server.ServerWebExchange;

import java.util.Map;

/**
 * 跨模块异常处理的公共工具。
 * <p>
 * common（WebMvc）、router（WebMvc）、gateway（WebFlux）三处 {@code GlobalExceptionHandler}
 * 共用本类的错误码格式化、错误明细构造与消息截断逻辑，避免实现漂移。
 * 本类<b>零 Web 依赖</b>（仅引用 {@code spring-web} 中的 {@link ServerWebExchange} 类型用于 traceId 解析），
 * 不引入任何 Servlet / WebFlux 专属 API，可在任意运行环境复用。
 * </p>
 */
public final class ExceptionHandlerSupport {

    private ExceptionHandlerSupport() {
    }

    /**
     * 从当前线程 MDC 取链路追踪 ID（Servlet 环境由各模块 TracingFilter 注入）。
     *
     * @return traceId，未设置时为 null
     */
    public static String getTraceIdFromMdc() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : null;
    }

    /**
     * 从 WebFlux 的 {@link ServerWebExchange} 属性中取 traceId（优先），兜底 MDC。
     * <p>WebFlux 环境下 MDC 基于 ThreadLocal 不跨线程传播，可靠的链路是通过
     * {@code TracingGlobalFilter} 写入 {@code exchange.getAttributes().put("traceId", ...)}。</p>
     *
     * @param exchange ServerWebExchange（可传 null）
     * @return traceId，未设置时为 null
     */
    public static String resolveTraceIdFromExchange(ServerWebExchange exchange) {
        if (exchange != null) {
            String tid = exchange.getAttribute("traceId");
            if (tid != null) {
                return tid;
            }
        }
        return getTraceIdFromMdc();
    }

    /** 生成模块限定错误码：{@code module + "_" + seq}（如 ROUTER_001），module 为空时回退 APP。 */
    public static String formatModuleCode(String moduleName, String seq) {
        return (moduleName == null ? "APP" : moduleName).toUpperCase() + "_" + seq;
    }

    /** 截断超长字符串，避免错误信息撑爆响应体或日志。 */
    public static String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    /** 构造 {@link ApiResponse.ErrorDetail}。 */
    public static ApiResponse.ErrorDetail buildErrorDetail(String code, String detail,
                                                           Map<String, String> fields, String traceId) {
        return new ApiResponse.ErrorDetail(code, detail, fields, traceId);
    }
}
