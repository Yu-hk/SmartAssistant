package com.example.smartassistant.gateway.filter;

import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 网关全链路追踪注入过滤器。
 * <p>
 * 在请求进入时生成/复用 traceId，并同时写入三处，确保异常处理器能可靠回填：
 * <ul>
 *   <li>{@code exchange.getAttributes().put("traceId", ...)} —— WebFlux 跨线程可靠传递，供异常处理器回填；</li>
 *   <li>{@code MDC.put("traceId", ...)} —— 同线程日志染色（注意 WebFlux 下 MDC 不跨线程传播）；</li>
 *   <li>{@code reactor.util.context.Context} —— 响应式链路透传兜底。</li>
 * </ul>
 * 优先级设为 {@code HIGHEST_PRECEDENCE}，确保所有后续过滤器与异常处理器都能读到 traceId。
 * 与 common 模块 {@code TracingFilter} 的 MDC key（{@code traceId}）保持一致。
 */
@Component
public class TracingGlobalFilter implements GlobalFilter, Ordered {

    /** 与 common 模块 MDC key 保持一致 */
    public static final String TRACE_ID_KEY = "traceId";

    private static final String HEADER_TRACE_ID = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(HEADER_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = "trace_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        final String resolved = traceId;
        exchange.getAttributes().put(TRACE_ID_KEY, resolved);
        MDC.put(TRACE_ID_KEY, resolved);
        return chain.filter(exchange)
                .contextWrite(ctx -> ctx.put(TRACE_ID_KEY, resolved))
                .doFinally(signal -> MDC.remove(TRACE_ID_KEY));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
