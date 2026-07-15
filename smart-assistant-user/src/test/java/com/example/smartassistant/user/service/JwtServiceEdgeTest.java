/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JwtService} 边界用例（补充 {@link JwtServiceTest}）。
 * <p>覆盖：过期 token 校验失败、Refresh Token 不含 userId claim 的取值语义。</p>
 */
@DisplayName("[user] JwtService 边界用例")
class JwtServiceEdgeTest {

    private static final String SECRET = "a2a-demo-secret-key-for-jwt-token-generation-2026";
    private final JwtService jwtService = new JwtService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtService, "secret", SECRET);
        ReflectionTestUtils.setField(jwtService, "expiration", 3600000L);
        ReflectionTestUtils.setField(jwtService, "refreshExpiration", 86400000L);
    }

    @Test
    @DisplayName("过期 Access Token：validateToken 应返回 false")
    void expiredAccessToken_validateFalse() {
        // 将过期时间设为负值，使生成的 token 立即过期
        ReflectionTestUtils.setField(jwtService, "expiration", -1000L);
        String token = jwtService.generateToken(1L, "testuser");
        assertNotNull(token);
        assertFalse(jwtService.validateToken(token, "testuser"),
                "已过期的 token 应校验失败");
    }

    @Test
    @DisplayName("Refresh Token 不含 userId：extractUserId 应返回 null")
    void refreshToken_withoutUserId_extractUserIdNull() {
        String refreshToken = jwtService.generateRefreshToken("testuser");
        assertNotNull(refreshToken);
        assertNull(jwtService.extractUserId(refreshToken),
                "Refresh Token 未写入 userId claim，取值应为 null");
        assertEquals("testuser", jwtService.extractUsername(refreshToken));
    }
}
