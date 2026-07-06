/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 请求审计与 RequestId 传播过滤器。
 *
 * <p>统一注入 X-Request-Id、记录请求路径/耗时/状态码，
 * 供下游各服务统一使用 requestId 做追踪溯源。
 *
 * <p>Order = -1（在 JWT 认证过滤器之后，业务路由之前）。
 * Order 越高执行越晚，故设为 -1 优先执行。
 */
@Component
public class RequestAuditFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestAuditFilter.class);

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.currentTimeMillis();

        // 注入或保留 RequestId
        String existingId = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        final String requestId = (existingId != null && !existingId.isBlank())
                ? existingId
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                .header(REQUEST_ID_HEADER, requestId)
                .build();

        ServerWebExchange modifiedExchange = exchange.mutate()
                .request(modifiedRequest)
                .build();

        return chain.filter(modifiedExchange).then(Mono.fromRunnable(() -> {
            long elapsed = System.currentTimeMillis() - startTime;
            int status = modifiedExchange.getResponse().getStatusCode() != null
                    ? modifiedExchange.getResponse().getStatusCode().value() : 0;
            final String path = modifiedRequest.getURI().getPath();
            final String method = modifiedRequest.getMethod() != null
                    ? modifiedRequest.getMethod().name() : "UNKNOWN";

            // 慢请求告警（超过 5 秒）
            if (elapsed > 5000) {
                log.warn("[Audit] 慢请求: method={}, path={}, status={}, elapsed={}ms, requestId={}",
                        method, path, status, elapsed, requestId);
            } else {
                log.info("[Audit] method={}, path={}, status={}, elapsed={}ms, requestId={}",
                        method, path, status, elapsed, requestId);
            }
        }));
    }

    @Override
    public int getOrder() {
        return -1; // 在 JWT 过滤器之前执行（JWT 的 Order 通常为 0 或更大）
    }
}
