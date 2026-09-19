package com.example.smartassistant.consumer.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Destructive privacy APIs verify the token themselves, even behind Gateway.
 * Never trust a direct caller's X-User-Id, refresh token, or revoked access token. */
@Component @Order(-100)
public class ProfilePrivacyAuthentication extends OncePerRequestFilter {
    private final SecretKey key;
    private final StringRedisTemplate redis;
    public ProfilePrivacyAuthentication(@Value("${jwt.secret:${JWT_SECRET:}}") String secret,StringRedisTemplate redis) {
        SecretKey parsed=null;
        try { parsed=Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); }
        catch(RuntimeException ignored) { /* Only privacy routes fail closed. */ }
        this.key=parsed;this.redis=redis;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getServletPath();
        if(path==null || path.isEmpty()) path=request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/api/privacy") || path.startsWith("/api/privacy/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(key==null){reject(response,503);return;}
        String header=request.getHeader("Authorization");
        if(header==null || !header.startsWith("Bearer ") || header.length()>8192){reject(response,401);return;}
        Long user;String jti;
        try {
            var claims=Jwts.parser().verifyWith(key).build().parseSignedClaims(header.substring(7)).getPayload();
            user=claims.get("userId",Long.class);jti=claims.getId();
            if(user==null || user<=0 || claims.getExpiration()==null || !"access".equals(claims.get("tokenType",String.class))
                    || jti==null || jti.isBlank() || jti.length()>128) {reject(response,401);return;}
        } catch(RuntimeException invalid){reject(response,401);return;}
        try {
            Boolean revoked=redis.hasKey("blacklist:"+jti);
            if(revoked==null){reject(response,503);return;}
            if(revoked){reject(response,401);return;}
        } catch(RuntimeException unavailable){reject(response,503);return;}
        request.setAttribute("profileAuthenticatedOwner",user);
        chain.doFilter(request,response);
    }
    private static void reject(HttpServletResponse response,int status) throws IOException {
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write(status==401?"{\"message\":\"请重新登录后管理画像。\"}":"{\"message\":\"暂时无法验证身份，请稍后再试。\"}");
    }
}
