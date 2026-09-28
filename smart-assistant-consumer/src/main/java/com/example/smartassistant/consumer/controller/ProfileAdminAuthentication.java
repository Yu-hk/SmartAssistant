package com.example.smartassistant.consumer.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Independent authentication for cross-user portrait data and destructive actions. */
@Component @Order(-101)
public class ProfileAdminAuthentication extends OncePerRequestFilter {
    private final SecretKey key;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    public ProfileAdminAuthentication(@Value("${jwt.secret:${JWT_SECRET:}}") String secret,
                                      StringRedisTemplate redis,JdbcTemplate jdbc) {
        SecretKey parsed=null;
        try { parsed=Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); }
        catch(RuntimeException ignored) { /* Fail closed only for these routes. */ }
        key=parsed;this.redis=redis;this.jdbc=jdbc;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getServletPath();
        if(path==null || path.isEmpty()) path=request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/api/admin/profiles") || path.startsWith("/api/admin/profiles/"));
    }

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        if(key==null){reject(response,503);return;}
        String header=request.getHeader("Authorization");
        if(header==null || !header.startsWith("Bearer ") || header.length()>8192){reject(response,401);return;}
        Long actor;String jti;
        try {
            var claims=Jwts.parser().verifyWith(key).build().parseSignedClaims(header.substring(7)).getPayload();
            actor=claims.get("userId",Long.class);jti=claims.getId();
            if(actor==null || actor<=0 || claims.getExpiration()==null || !"access".equals(claims.get("tokenType",String.class))
                    || jti==null || jti.isBlank() || jti.length()>128){reject(response,401);return;}
            if(!"ROLE_ADMIN".equals(claims.get("role",String.class))){reject(response,403);return;}
        } catch(RuntimeException invalid){reject(response,401);return;}
        try {
            Boolean revoked=redis.hasKey("blacklist:"+jti);
            if(revoked==null){reject(response,503);return;}
            if(revoked){reject(response,401);return;}
            var roles=jdbc.queryForList("SELECT role FROM users WHERE id=?",String.class,actor);
            if(roles.isEmpty() || !"ROLE_ADMIN".equals(roles.getFirst())){reject(response,403);return;}
        } catch(RuntimeException unavailable){reject(response,503);return;}
        request.setAttribute("profileAdminActor",actor);
        chain.doFilter(request,response);
    }

    private static void reject(HttpServletResponse response,int status) throws IOException {
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"无法验证管理员权限。\"}");
    }
}
