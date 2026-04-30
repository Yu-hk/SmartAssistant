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
import java.util.Arrays;
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

    @Value("${gateway.security.white-list}")
    private String whiteListStr;

    public GlobalJwtAuthFilter(JwtUtil jwtUtil, ReactiveStringRedisTemplate redisTemplate) {
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 检查是否在白名单中
        List<String> whiteList = Arrays.asList(whiteListStr.split(","));
        if (isWhiteListPath(path, whiteList)) {
            log.debug("[JWT] 路径 {} 在白名单中，跳过认证", path);
            return chain.filter(exchange);
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
                    
                    // 将用户信息添加到请求头中传递给下游服务
                    ServerHttpRequest.Builder builder = request.mutate();
                    builder.header("X-User-Id", userId);
                    builder.header("X-User-Username", username);
                    builder.header("X-User-Role", role);
                    
                    log.info("[JWT] 认证成功: 用户ID={}, 用户名={}, 角色={}", userId, username, role);
                    
                    return chain.filter(exchange.mutate().request(builder.build()).build());
                });

        } catch (Exception e) {
            log.error("[JWT] 认证过程异常: {}", e.getMessage(), e);
            return unauthorizedResponse(exchange, "Authentication error: " + e.getMessage());
        }
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
     * 返回 401 未授权响应
     */
    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);

        String body = "{\"error\":\"Unauthorized\",\"message\":\"" + message + "\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json");
        response.getHeaders().setContentLength(bytes.length);
        
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    @Override
    public int getOrder() {
        return -100; // 高优先级，在其他过滤器之前执行
    }
}
