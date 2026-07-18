package com.example.smartassistant.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtService 边界测试（过期 / 角色隔离 / 密钥防篡改）。
 * <p>通过 {@link ReflectionTestUtils} 注入 {@code @Value} 字段，避免启动 Spring 容器。
 * 过期场景通过将 {@code expiration} 设为负值使生成的 Token 立即过期来验证。</p>
 */
@DisplayName("[User] JwtService 边界测试（过期 / 角色 / 篡改）")
class JwtServiceEdgeTest {

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
    @DisplayName("过期 Token：expiration 设为负值后生成的 Token 立即过期，校验应失败")
    void expiredTokenShouldFail() {
        ReflectionTestUtils.setField(jwtService, "expiration", -1000L);
        String token = jwtService.generateToken(1L, "expiree");
        assertFalse(jwtService.validateToken(token, "expiree"),
                "过期 Token 不应通过校验");
    }

    @Test
    @DisplayName("角色隔离：ROLE_USER 的 Token 不能被其他用户名校验通过")
    void roleAndSubjectIsolation() {
        String userToken = jwtService.generateToken(1L, "alice", "ROLE_USER");
        // 以不同用户名（admin）校验应失败——subject 不匹配
        assertFalse(jwtService.validateToken(userToken, "admin"),
                "普通用户 Token 不应以其他用户名通过校验");
        // 角色声明应正确写入并可提取
        String role = jwtService.extractClaim(userToken, c -> c.get("role", String.class));
        assertEquals("ROLE_USER", role);
    }

    @Test
    @DisplayName("密钥防篡改：不同密钥签发的 Token 不应被验证通过")
    void tamperedSecretFails() {
        String token = jwtService.generateToken(1L, "bob");
        JwtService otherService = new JwtService();
        ReflectionTestUtils.setField(otherService, "secret", "a-completely-different-secret-key-0000");
        ReflectionTestUtils.setField(otherService, "expiration", 3600000L);
        ReflectionTestUtils.setField(otherService, "refreshExpiration", 86400000L);
        assertFalse(otherService.validateToken(token, "bob"),
                "不同密钥签发的 Token 不应被验证通过");
    }

    @Test
    @DisplayName("Refresh Token：用户名一致可校验，不一致则失败")
    void refreshTokenValidate() {
        String refresh = jwtService.generateRefreshToken("carol");
        assertTrue(jwtService.validateToken(refresh, "carol"));
        assertFalse(jwtService.validateToken(refresh, "dave"));
    }
}
