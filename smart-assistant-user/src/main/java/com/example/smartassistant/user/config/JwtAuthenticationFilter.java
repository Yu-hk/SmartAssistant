package com.example.smartassistant.user.config;

import com.example.smartassistant.user.service.JwtService;
import com.example.smartassistant.user.service.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器（增强版：验证 Session）
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final SessionService sessionService;
    
    public JwtAuthenticationFilter(
            JwtService jwtService,
            UserDetailsService userDetailsService,
            SessionService sessionService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.sessionService = sessionService;
    }
    
    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        
        final String requestURI = request.getRequestURI();
        
        // ⭐ 跳过公开路径的 JWT 检查（避免不必要的警告日志）
        if (isPublicPath(requestURI)) {
            filterChain.doFilter(request, response);
            return;
        }
        
        final String authHeader = request.getHeader("Authorization");
        
        // ⭐ 添加调试日志
        log.debug("[JWT Filter] 请求: {} {}, Authorization Header: {}", 
                request.getMethod(), requestURI, 
                authHeader != null ? "Present" : "Missing");
        
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("[JWT Filter] 缺少或无效的 Authorization Header: {}", requestURI);
            filterChain.doFilter(request, response);
            return;
        }
        
        try {
            final String jwt = authHeader.substring(7);
            final String username = jwtService.extractUsername(jwt);
            
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = this.userDetailsService.loadUserByUsername(username);
                
                // 1. 验证 JWT Token
                if (jwtService.validateToken(jwt, username)) {
                    // 2. 验证 Session（JWT + Redis）
                    String tokenId = jwtService.extractTokenId(jwt);
                    if (sessionService.validateSession(tokenId)) {
                        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );
                        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authToken);
                        
                        log.debug("JWT + Session 认证成功: {}", username);
                    } else {
                        log.warn("Session 已失效: tokenId={}", tokenId);
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.getWriter().write("{\"error\": \"会话已失效，请重新登录\"}");
                        return;
                    }
                }
            }
        } catch (Exception e) {
            log.error("JWT 认证失败: {}", e.getMessage());
        }
        
        filterChain.doFilter(request, response);
    }
    
    /**
     * 判断是否为公开路径（不需要 JWT 认证）
     */
    private boolean isPublicPath(String requestURI) {
        return requestURI.startsWith("/api/auth/")
            || requestURI.startsWith("/api/food/")
            || requestURI.startsWith("/api/travel/")
            || requestURI.startsWith("/api/session/")
            || requestURI.startsWith("/actuator/");  // ⭐ 放行监控端点
    }
}
