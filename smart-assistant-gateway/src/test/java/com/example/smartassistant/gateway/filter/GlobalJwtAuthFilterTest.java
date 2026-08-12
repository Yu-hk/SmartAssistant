/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.gateway.filter;

import com.example.smartassistant.gateway.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * GlobalJwtAuthFilter 单元测试
 * 验证白名单路径匹配逻辑
 */
class GlobalJwtAuthFilterTest {

    private GlobalJwtAuthFilter filter;
    private JwtUtil jwtUtil;
    private ReactiveStringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        jwtUtil = mock(JwtUtil.class);
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        List<String> whiteList = List.of(
                "/api/auth/login",
                "/api/auth/register",
                "/api/public/**",
                "/actuator/health"
        );
        filter = new GlobalJwtAuthFilter(jwtUtil, redisTemplate, whiteList);
    }

    /**
     * 通过反射调用私有方法 isWhiteListPath
     */
    private boolean invokeIsWhiteListPath(String path, List<String> whiteList) throws Exception {
        Method method = GlobalJwtAuthFilter.class.getDeclaredMethod("isWhiteListPath", String.class, List.class);
        method.setAccessible(true);
        return (boolean) method.invoke(filter, path, whiteList);
    }

    // ========== 精确匹配 ==========

    @Test
    void exactPathShouldMatch() throws Exception {
        assertTrue(invokeIsWhiteListPath("/api/auth/login",
                List.of("/api/auth/login")));
    }

    @Test
    void exactPathWithDifferentCaseShouldNotMatch() throws Exception {
        // isWhiteListPath 使用 path.equals(trimmedPath)，区分大小写
        assertFalse(invokeIsWhiteListPath("/API/AUTH/LOGIN",
                List.of("/api/auth/login")));
    }

    @Test
    void nonMatchingPathShouldNotMatch() throws Exception {
        assertFalse(invokeIsWhiteListPath("/api/other/endpoint",
                List.of("/api/auth/login")));
    }

    // ========== 通配符匹配（/**） ==========

    @Test
    void wildcardPrefixShouldMatch() throws Exception {
        assertTrue(invokeIsWhiteListPath("/api/public/some/endpoint",
                List.of("/api/public/**")));
    }

    @Test
    void wildcardExactRootShouldMatch() throws Exception {
        assertTrue(invokeIsWhiteListPath("/api/public",
                List.of("/api/public/**")));
    }

    @Test
    void wildcardDeepPathShouldMatch() throws Exception {
        assertTrue(invokeIsWhiteListPath("/api/public/a/b/c/d/e",
                List.of("/api/public/**")));
    }

    @Test
    void wildcardShouldNotMatchDifferentPrefix() throws Exception {
        assertFalse(invokeIsWhiteListPath("/api/private/data",
                List.of("/api/public/**")));
    }

    @Test
    void partialPrefixShouldNotMatch() throws Exception {
        // /api/pub 不是 /api/public 的前缀（缺少 lic）
        assertFalse(invokeIsWhiteListPath("/api/pub/something",
                List.of("/api/public/**")));
    }

    // ========== 边界条件 ==========

    @Test
    void emptyPathShouldNotMatch() throws Exception {
        assertFalse(invokeIsWhiteListPath("", List.of("/api/auth/login")));
    }

    @Test
    void rootPathShouldMatchWildcard() throws Exception {
        assertTrue(invokeIsWhiteListPath("/api/public", List.of("/api/public/**")));
    }

    @Test
    void multipleWhiteListEntriesShouldMatchAny() throws Exception {
        List<String> whiteList = List.of("/api/auth/login", "/api/public/**", "/actuator/health");
        assertTrue(invokeIsWhiteListPath("/api/public/test", whiteList));
        assertTrue(invokeIsWhiteListPath("/actuator/health", whiteList));
        assertTrue(invokeIsWhiteListPath("/api/auth/login", whiteList));
        assertFalse(invokeIsWhiteListPath("/api/secret/data", whiteList));
    }

    @Test
    void emptyWhiteListShouldNotMatchAnything() throws Exception {
        assertFalse(invokeIsWhiteListPath("/api/auth/login", List.of()));
    }

    @Test
    void authenticatedRequestShouldReplaceSpoofedIdentityHeaders() {
        when(jwtUtil.getTokenIdFromToken("valid-token")).thenReturn("token-id");
        when(redisTemplate.hasKey("blacklist:token-id")).thenReturn(Mono.just(false));
        when(jwtUtil.validateToken("valid-token")).thenReturn(true);
        when(jwtUtil.getUserIdFromToken("valid-token")).thenReturn("42");
        when(jwtUtil.getUsernameFromToken("valid-token")).thenReturn("alice");
        when(jwtUtil.getRoleFromToken("valid-token")).thenReturn("ROLE_USER");

        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        ArgumentCaptor<ServerWebExchange> exchangeCaptor = ArgumentCaptor.forClass(ServerWebExchange.class);
        when(chain.filter(exchangeCaptor.capture())).thenReturn(Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                        .header("X-User-Id", "999")
                        .header("X-User-Username", "attacker")
                        .header("X-User-Role", "ROLE_ADMIN")
                        .header("X-User-Permissions", "all")
                        .header("x-uSeR-Debug", "true")
                        .build());

        filter.filter(exchange, chain).block();

        HttpHeaders forwarded = exchangeCaptor.getValue().getRequest().getHeaders();
        assertEquals(List.of("42"), forwarded.get("X-User-Id"));
        assertEquals(List.of("alice"), forwarded.get("X-User-Username"));
        assertEquals(List.of("ROLE_USER"), forwarded.get("X-User-Role"));
        assertFalse(forwarded.containsHeader("X-User-Permissions"));
        assertFalse(forwarded.containsHeader("X-User-Debug"));
    }

    @Test
    void publicRequestShouldStripSpoofedIdentityHeaders() {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        ArgumentCaptor<ServerWebExchange> exchangeCaptor = ArgumentCaptor.forClass(ServerWebExchange.class);
        when(chain.filter(exchangeCaptor.capture())).thenReturn(Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login")
                        .header("X-User-Id", "999")
                        .header("X-User-Username", "attacker")
                        .header("X-User-Role", "ROLE_ADMIN")
                        .header("X-User-Permissions", "all")
                        .header("x-uSeR-Debug", "true")
                        .build());

        filter.filter(exchange, chain).block();

        HttpHeaders forwarded = exchangeCaptor.getValue().getRequest().getHeaders();
        assertFalse(forwarded.containsHeader("X-User-Id"));
        assertFalse(forwarded.containsHeader("X-User-Username"));
        assertFalse(forwarded.containsHeader("X-User-Role"));
        assertFalse(forwarded.containsHeader("X-User-Permissions"));
        assertFalse(forwarded.containsHeader("X-User-Debug"));
        verifyNoInteractions(jwtUtil, redisTemplate);
    }

    @Test
    void authenticatedOrdinaryUserCannotReachAdminApi() {
        stubAuthenticatedToken("ROLE_USER");
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/admin/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                        .build());

        filter.filter(exchange, chain).block();

        assertEquals(403, exchange.getResponse().getStatusCode().value());
        verifyNoInteractions(chain);
    }

    @Test
    void roleMatchingIsExactForAdminApi() {
        stubAuthenticatedToken("role_admin");
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/admin/stats")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                        .build());

        filter.filter(exchange, chain).block();

        assertEquals(403, exchange.getResponse().getStatusCode().value());
        verifyNoInteractions(chain);
    }

    @Test
    void exactAdministratorRoleCanReachAdminApi() {
        stubAuthenticatedToken("ROLE_ADMIN");
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/admin/stats")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                        .build());

        filter.filter(exchange, chain).block();

        verify(chain).filter(any(ServerWebExchange.class));
    }

    @Test
    void legacyManagementAliasIsProtectedToo() {
        stubAuthenticatedToken("ROLE_USER");
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/stats")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                        .build());

        filter.filter(exchange, chain).block();

        assertEquals(403, exchange.getResponse().getStatusCode().value());
        verifyNoInteractions(chain);
    }

    @Test
    void authenticatedCustomerCanRecordFaqHitButCannotManageFaqs() {
        stubAuthenticatedToken("ROLE_USER");
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/faq/17/hit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                        .build());

        filter.filter(exchange, chain).block();

        verify(chain).filter(any(ServerWebExchange.class));
    }

    private void stubAuthenticatedToken(String role) {
        when(jwtUtil.getTokenIdFromToken("valid-token")).thenReturn("token-id");
        when(redisTemplate.hasKey("blacklist:token-id")).thenReturn(Mono.just(false));
        when(jwtUtil.validateToken("valid-token")).thenReturn(true);
        when(jwtUtil.getUserIdFromToken("valid-token")).thenReturn("42");
        when(jwtUtil.getUsernameFromToken("valid-token")).thenReturn("alice");
        when(jwtUtil.getRoleFromToken("valid-token")).thenReturn(role);
    }
}
