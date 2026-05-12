/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.gateway.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类
 * 用于生成和验证 JWT Token
 */
@Component
@Slf4j
public class JwtUtil {
    
    @Value("${jwt.secret}")
    private String secret;
    
    @Value("${jwt.expiration:86400000}")
    private Long expiration;
    
    /**
     * 获取签名密钥
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
    
    /**
     * 解析 Token，返回 Claims
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        } catch (Exception e) {
            log.error("[JWT] Token 解析失败: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid token");
        }
    }
    
    /**
     * 验证 Token 是否有效
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = parseToken(token);
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            log.debug("[JWT] Token 验证失败: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 从 Token 中获取用户 ID（数字类型）
     * 注意：userId 存在 claims["userId"] 中，而不是 subject
     */
    public String getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        // 从 claims 中获取 userId（Long 类型转为 String）
        Long userId = claims.get("userId", Long.class);
        if (userId != null) {
            return String.valueOf(userId);
        }
        // 兜底：如果 userId 不存在，返回 null
        log.warn("[JWT] Token 中未找到 userId claim");
        return null;
    }
    
    /**
     * 从 Token 中获取用户名
     */
    public String getUsernameFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("username", String.class);
    }
    
    /**
     * 从 Token 中获取角色
     */
    public String getRoleFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("role", String.class);
    }
    
    /**
     * ⭐ 从 Token 中提取 Token ID (jti)
     * 用于黑名单机制
     */
    public String getTokenIdFromToken(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.get("jti", String.class);
        } catch (Exception e) {
            log.debug("[JWT] 提取 jti 失败: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 检查 Token 是否过期
     */
    public boolean isTokenExpired(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return true;
        }
    }
}
