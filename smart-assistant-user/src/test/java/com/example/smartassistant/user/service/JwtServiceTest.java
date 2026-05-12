/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtService 单元测试
 */
class JwtServiceTest {

    private static final String SECRET = "a2a-demo-secret-key-for-jwt-token-generation-2026";
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", SECRET);
        ReflectionTestUtils.setField(jwtService, "expiration", 3600000L);
        ReflectionTestUtils.setField(jwtService, "refreshExpiration", 86400000L);
    }

    @Test
    void generateAndValidateToken() {
        String token = jwtService.generateToken(1L, "testuser");
        assertNotNull(token);
        assertTrue(jwtService.validateToken(token, "testuser"));
    }

    @Test
    void generateTokenWithRole() {
        String token = jwtService.generateToken(1L, "admin", "ROLE_ADMIN");
        assertNotNull(token);
        assertEquals("admin", jwtService.extractUsername(token));
        assertEquals(Long.valueOf(1L), jwtService.extractUserId(token));
        assertTrue(jwtService.validateToken(token, "admin"));
    }

    @Test
    void extractUserId() {
        String token = jwtService.generateToken(42L, "user42");
        assertEquals(Long.valueOf(42L), jwtService.extractUserId(token));
    }

    @Test
    void extractUsername() {
        String token = jwtService.generateToken(1L, "johndoe");
        assertEquals("johndoe", jwtService.extractUsername(token));
    }

    @Test
    void extractTokenId() {
        String token = jwtService.generateToken(1L, "testuser");
        String jti = jwtService.extractTokenId(token);
        assertNotNull(jti);
        assertFalse(jti.isBlank());
    }

    @Test
    void generateRefreshToken() {
        String refreshToken = jwtService.generateRefreshToken("testuser");
        assertNotNull(refreshToken);
        assertTrue(jwtService.validateToken(refreshToken, "testuser"));
    }

    @Test
    void wrongUsernameShouldFail() {
        String token = jwtService.generateToken(1L, "realuser");
        assertFalse(jwtService.validateToken(token, "wronguser"));
    }

    @Test
    void invalidTokenShouldFail() {
        assertFalse(jwtService.validateToken("invalid-token", "user"));
    }

    @Test
    void tokenWithWrongUsernameShouldReturnFalse() {
        assertFalse(jwtService.validateToken("bad-token-that-is-not-a-valid-jwt", "user"));
    }
}
