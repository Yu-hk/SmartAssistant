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
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * GlobalJwtAuthFilter 单元测试
 * 验证白名单路径匹配逻辑
 */
class GlobalJwtAuthFilterTest {

    private GlobalJwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
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
}
