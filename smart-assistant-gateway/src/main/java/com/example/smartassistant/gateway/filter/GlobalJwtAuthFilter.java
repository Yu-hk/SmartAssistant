/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.gateway.filter;

import com.example.smartassistant.gateway.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 全局 JWT 认证过滤器
 * 拦截所有请求，验证 JWT Token
 */
@Component
@Slf4j
public class GlobalJwtAuthFilter implements GlobalFilter, Ordered {

    private final JwtUtil jwtUtil;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final List<String> whiteList;

    public GlobalJwtAuthFilter(JwtUtil jwtUtil, ReactiveStringRedisTemplate redisTemplate,
                               @Value("${gateway.security.white-list}") List<String> whiteList) {
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
        this.whiteList = whiteList;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = stripUntrustedIdentityHeaders(exchange.getRequest());
        ServerWebExchange sanitizedExchange = exchange.mutate().request(request).build();
        String path = request.getURI().getPath();

        // 检查是否在白名单中
        if (isWhiteListPath(path, whiteList)) {
            log.debug("[JWT] 路径 {} 在白名单中，跳过认证", path);
            return chain.filter(sanitizedExchange);
        }

        // 检查是否有 Authorization 头
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("[JWT] 缺少有效的 Authorization 头: {}", authHeader);
            return unauthorizedResponse(exchange, "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7); // 移除 "Bearer " 前缀

        try {
            // 1. 检查 Token 是否在黑名单中（已登出）
            return redisTemplate.hasKey("blacklist:" + jwtUtil.getTokenIdFromToken(token))
                .defaultIfEmpty(false)
                .flatMap(isBlacklisted -> {
                    if (Boolean.TRUE.equals(isBlacklisted)) {
                        log.warn("[JWT] Token 已在黑名单中");
                        return unauthorizedResponse(exchange, "Token has been revoked");
                    }
                    
                    // 2. 验证 Token
                    if (!jwtUtil.validateToken(token)) {
                        log.warn("[JWT] Token 验证失败");
                        return unauthorizedResponse(exchange, "Invalid token");
                    }
                    
                    // 3. 从 Token 中提取用户信息并传递给下游服务
                    String userId = jwtUtil.getUserIdFromToken(token);
                    String username = jwtUtil.getUsernameFromToken(token);
                    String role = jwtUtil.getRoleFromToken(token);

                    // Administration routes are authorized centrally at the
                    // trust boundary. Downstream controllers repeat this exact
                    // role check as defense in depth, but a non-admin request
                    // must never be forwarded to a management service.
                    if (isAdminOnlyPath(path) && !"ROLE_ADMIN".equals(role)) {
                        log.warn("[JWT] Rejected non-admin request to management path: path={}, role={}",
                                path, role);
                        return forbiddenResponse(exchange,
                                "Administrator privileges are required");
                    }
                    
                    // 将用户信息添加到请求头中传递给下游服务
                    ServerHttpRequest authenticatedRequest = request.mutate()
                            .headers(headers -> {
                                headers.set("X-User-Id", userId);
                                headers.set("X-User-Username", username);
                                headers.set("X-User-Role", role);
                            })
                            .build();
                    
                    log.info("[JWT] 认证成功: 用户ID={}, 用户名={}, 角色={}", userId, username, role);
                    
                    return chain.filter(sanitizedExchange.mutate().request(authenticatedRequest).build());
                });

        } catch (Exception e) {
            log.error("[JWT] 认证过程异常: {}", e.getMessage(), e);
            return unauthorizedResponse(exchange, "认证服务异常，请稍后重试");
        }
    }

    /**
     * Identity headers are trusted only when this gateway derives them from a
     * validated access token. Always remove client-provided values first,
     * including on public endpoints, so they cannot be forwarded downstream.
     */
    private ServerHttpRequest stripUntrustedIdentityHeaders(ServerHttpRequest request) {
        return request.mutate()
                .headers(headers -> {
                    List<String> untrustedHeaders = headers.keySet().stream()
                            .filter(name -> name.regionMatches(true, 0, "X-User-", 0, 7))
                            .toList();
                    untrustedHeaders.forEach(headers::remove);
                })
                .build();
    }

    /**
     * 检查路径是否在白名单中
     */
    private boolean isWhiteListPath(String path, List<String> whiteList) {
        for (String whitePath : whiteList) {
            String trimmedPath = whitePath.trim();
            if (trimmedPath.endsWith("/**")) {
                String prefix = trimmedPath.substring(0, trimmedPath.length() - 3);
                if (path.startsWith(prefix)) {
                    return true;
                }
            } else {
                if (path.equals(trimmedPath)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * New administration APIs plus the compatibility management aliases that
     * remain temporarily available to the previous frontend.
     */
    private boolean isAdminOnlyPath(String path) {
        // Authenticated customer traffic may report that a suggested FAQ was
        // opened. Only this counter endpoint is shared; FAQ CRUD stays admin-only.
        if (path.matches("^/(?:assistant/)?api/faq/[^/]+/hit$")) {
            return false;
        }
        return path.equals("/api/admin")
                || path.startsWith("/api/admin/")
                || path.equals("/assistant/api/admin")
                || path.startsWith("/assistant/api/admin/")
                || path.equals("/api/stats")
                || path.equals("/api/faq")
                || path.startsWith("/api/faq/")
                || path.equals("/api/check-login")
                || path.equals("/api/save-env-config")
                || path.equals("/api/analytics")
                || path.startsWith("/api/analytics/")
                || path.equals("/api/prompt-monitoring")
                || path.startsWith("/api/prompt-monitoring/")
                || path.equals("/api/monitor")
                || path.startsWith("/api/monitor/")
                || path.equals("/api/math/cache/stats");
    }
    
    /**
     * 返回 401 未授权响应
     */
    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);

        // 使用 JSON 序列化避免消息拼接导致的格式问题
        String body = String.format("{\"error\":\"Unauthorized\",\"message\":\"%s\"}",
                message.replace("\"", "\\\"").replace("\n", "\\n"));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json");
        response.getHeaders().setContentLength(bytes.length);
        
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private Mono<Void> forbiddenResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        String body = String.format("{\"error\":\"Forbidden\",\"message\":\"%s\"}",
                message.replace("\"", "\\\"").replace("\n", "\\n"));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
        response.getHeaders().setContentLength(bytes.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    @Override
    public int getOrder() {
        return -100; // 高优先级，在其他过滤器之前执行
    }
}
