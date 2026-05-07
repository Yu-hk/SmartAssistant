package com.example.smartassistant.gateway.util;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtUtil 单元测试
 * 验证 JWT Token 的创建、验证、信息提取等核心功能
 */
class JwtUtilTest {

    private static final String SECRET = "a2a-demo-secret-key-for-jwt-token-generation-2026-very-long";
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expiration", 3600000L); // 1小时
    }

    /**
     * 生成测试用 JWT Token
     */
    private String generateTestToken(String subject, Long userId, String username,
                                     String role, Date expiration, String jti) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(subject)
                .claim("userId", userId)
                .claim("username", username)
                .claim("role", role)
                .id(jti)
                .issuedAt(new Date())
                .expiration(expiration)
                .signWith(key)
                .compact();
    }

    // ========== validateToken ==========

    @Test
    void validTokenShouldPass() {
        String token = generateTestToken("admin", 1L, "admin", "USER",
                new Date(System.currentTimeMillis() + 3600000), UUID.randomUUID().toString());
        assertTrue(jwtUtil.validateToken(token));
    }

    @Test
    void expiredTokenShouldFail() {
        String token = generateTestToken("admin", 1L, "admin", "USER",
                new Date(System.currentTimeMillis() - 1000), UUID.randomUUID().toString());
        assertFalse(jwtUtil.validateToken(token));
    }

    @Test
    void malformedTokenShouldFail() {
        assertFalse(jwtUtil.validateToken("invalid-token-string"));
    }

    @Test
    void emptyTokenShouldFail() {
        assertFalse(jwtUtil.validateToken(""));
    }

    @Test
    void nullTokenShouldFail() {
        assertFalse(jwtUtil.validateToken(null));
    }

    @Test
    void tokenWithWrongSecretShouldFail() {
        // 使用不同密钥生成的 Token
        SecretKey wrongKey = Keys.hmacShaKeyFor("different-secret-key-that-is-not-the-right-one".getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject("admin")
                .claim("userId", 1L)
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(wrongKey)
                .compact();
        assertFalse(jwtUtil.validateToken(token));
    }

    // ========== getUserIdFromToken ==========

    @Test
    void getUserIdFromValidToken() {
        String token = generateTestToken("admin", 42L, "admin", "USER",
                new Date(System.currentTimeMillis() + 3600000), UUID.randomUUID().toString());
        assertEquals("42", jwtUtil.getUserIdFromToken(token));
    }

    @Test
    void getUserIdWithoutClaimShouldReturnNull() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject("admin")
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(key)
                .compact();
        assertNull(jwtUtil.getUserIdFromToken(token));
    }

    // ========== getUsernameFromToken ==========

    @Test
    void getUsernameFromValidToken() {
        String token = generateTestToken("admin", 1L, "testuser", "USER",
                new Date(System.currentTimeMillis() + 3600000), UUID.randomUUID().toString());
        assertEquals("testuser", jwtUtil.getUsernameFromToken(token));
    }

    // ========== getRoleFromToken ==========

    @Test
    void getRoleFromValidToken() {
        String token = generateTestToken("admin", 1L, "admin", "ADMIN",
                new Date(System.currentTimeMillis() + 3600000), UUID.randomUUID().toString());
        assertEquals("ADMIN", jwtUtil.getRoleFromToken(token));
    }

    // ========== getTokenIdFromToken ==========

    @Test
    void getTokenIdFromValidToken() {
        String jti = UUID.randomUUID().toString();
        String token = generateTestToken("admin", 1L, "admin", "USER",
                new Date(System.currentTimeMillis() + 3600000), jti);
        assertEquals(jti, jwtUtil.getTokenIdFromToken(token));
    }

    @Test
    void getTokenIdFromInvalidTokenShouldReturnNull() {
        assertNull(jwtUtil.getTokenIdFromToken("bad-token"));
    }

    // ========== isTokenExpired ==========

    @Test
    void nonExpiredTokenShouldNotBeExpired() {
        String token = generateTestToken("admin", 1L, "admin", "USER",
                new Date(System.currentTimeMillis() + 3600000), UUID.randomUUID().toString());
        assertFalse(jwtUtil.isTokenExpired(token));
    }

    @Test
    void expiredTokenShouldBeExpired() {
        String token = generateTestToken("admin", 1L, "admin", "USER",
                new Date(System.currentTimeMillis() - 1000), UUID.randomUUID().toString());
        assertTrue(jwtUtil.isTokenExpired(token));
    }

    @Test
    void invalidTokenShouldBeTreatedAsExpired() {
        assertTrue(jwtUtil.isTokenExpired("bad-token"));
    }

    // ========== 边界条件 ==========

    @Test
    void tokenAtExactExpiryTimeShouldBeValidOrExpired() {
        // 创建刚刚过期的 Token（1毫秒过期）
        String token = generateTestToken("admin", 1L, "admin", "USER",
                new Date(System.currentTimeMillis() - 1), UUID.randomUUID().toString());
        assertTrue(jwtUtil.isTokenExpired(token));
    }

    @Test
    void zeroUserIdShouldBeHandledCorrectly() {
        String token = generateTestToken("admin", 0L, "admin", "USER",
                new Date(System.currentTimeMillis() + 3600000), UUID.randomUUID().toString());
        assertEquals("0", jwtUtil.getUserIdFromToken(token));
    }
}
