package com.example.smartassistant.user.service;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 服务类
 */
@Service
public class JwtService {
    
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    
    @Value("${jwt.secret:a2a-demo-secret-key-for-jwt-token-generation-2026}")
    private String secret;
    
    @Value("${jwt.expiration:3600000}")  // 默认 1 小时
    private long expiration;
    
    @Value("${jwt.refresh-expiration:86400000}")  // 默认 24 小时
    private long refreshExpiration;
    
    /**
     * 生成 Access Token（包含角色信息）
     */
    public String generateToken(Long userId, String username) {
        return generateToken(userId, username, "ROLE_USER");  // 默认普通用户
    }

    /**
     * 生成 Access Token（含角色）
     */
    public String generateToken(Long userId, String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("role", role);  // ⭐ 写入角色
        
        // 生成唯一的 Token ID (jti)
        String tokenId = UUID.randomUUID().toString();
        claims.put("jti", tokenId);
        
        return createToken(claims, username, expiration);
    }
    
    /**
     * 获取 Token ID (jti)
     */
    public String extractTokenId(String token) {
        return extractClaim(token, claims -> claims.get("jti", String.class));
    }
    
    /**
     * 生成 Refresh Token
     */
    public String generateRefreshToken(String username) {
        Map<String, Object> claims = new HashMap<>();
        return createToken(claims, username, refreshExpiration);
    }
    
    /**
     * 创建 Token
     */
    private String createToken(Map<String, Object> claims, String subject, long expirationTime) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationTime);
        
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }
    
    /**
     * 从 Token 中提取用户名
     */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }
    
    /**
     * 从 Token 中提取用户 ID
     */
    public Long extractUserId(String token) {
        return extractClaim(token, claims -> claims.get("userId", Long.class));
    }
    
    /**
     * 提取任意 Claim
     */
    public <T> T extractClaim(String token, java.util.function.Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }
    
    /**
     * 提取所有 Claims
     */
    private Claims extractAllClaims(String token) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
    
    /**
     * 验证 Token 是否有效
     */
    public boolean validateToken(String token, String username) {
        try {
            final String extractedUsername = extractUsername(token);
            return (extractedUsername.equals(username) && !isTokenExpired(token));
        } catch (JwtException e) {
            log.error("JWT Token 验证失败: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 检查 Token 是否过期
     */
    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }
    
    /**
     * 提取过期时间
     */
    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }
}
