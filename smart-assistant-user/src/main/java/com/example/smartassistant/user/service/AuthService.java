package com.example.smartassistant.user.service;

import com.example.smartassistant.user.mapper.UserMapper;
import com.example.smartassistant.user.model.User;
import com.example.smartassistant.user.model.dto.AuthResponse;
import com.example.smartassistant.user.model.dto.LoginRequest;
import com.example.smartassistant.user.model.dto.RegisterRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证服务
 */
@Service
public class AuthService {
    
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final SessionService sessionService;
    
    public AuthService(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuthenticationManager authenticationManager,
            SessionService sessionService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.sessionService = sessionService;
    }
    
    /**
     * 用户注册
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // 检查用户名是否已存在
        if (userMapper.existsByUsername(request.getUsername())) {
            throw new RuntimeException("用户名已存在");
        }
        
        // 创建新用户
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setRole("ROLE_USER");  // ⭐ 默认角色：普通用户
        
        userMapper.insert(user);
        
        log.info("用户注册成功: {}", request.getUsername());
        
        // 生成 Token
        return generateAuthResponse(user);
    }
    
    /**
     * 用户登录
     */
    public AuthResponse login(LoginRequest request, String ipAddress, String userAgent) {
        // 认证用户
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(),
                        request.getPassword()
                )
        );
        
        SecurityContextHolder.getContext().setAuthentication(authentication);
        
        // 查找用户
        User user = userMapper.findByUsername(request.getUsername());
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        
        log.info("用户登录成功: {}", request.getUsername());
        
        // 生成 Token
        AuthResponse response = generateAuthResponse(user);
        
        // 创建会话（JWT + Redis）
        String tokenId = jwtService.extractTokenId(response.getToken());
        sessionService.createSession(user.getId(), tokenId, ipAddress, userAgent);
        
        return response;
    }
    
    /**
     * 刷新 Token
     */
    public AuthResponse refreshToken(String refreshToken) {
        String username = jwtService.extractUsername(refreshToken);
        
        if (username == null) {
            throw new RuntimeException("无效的 Refresh Token");
        }
        
        User user = userMapper.findByUsername(username);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        
        // 验证 Refresh Token
        if (!jwtService.validateToken(refreshToken, username)) {
            throw new RuntimeException("Refresh Token 已过期");
        }
        
        return generateAuthResponse(user);
    }
    
    /**
     * 获取当前用户信息（通过 SecurityContext）
     */
    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("未登录");
        }
        
        String username = authentication.getName();
        User user = userMapper.findByUsername(username);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        return user;
    }

    /**
     * 通过 Token 获取当前用户信息（用于 /me 接口）
     */
    public User getCurrentUser(String token) {
        if (token == null || token.isBlank()) {
            throw new RuntimeException("未登录");
        }

        // 从 Token 中提取用户 ID
        Long userId = jwtService.extractUserId(token);
        log.info("[AuthService] /me 接口: 提取的 userId={}, username={}", userId, jwtService.extractUsername(token));
        if (userId == null) {
            throw new RuntimeException("Token无效");
        }

        // 验证 Token 是否过期
        if (!jwtService.validateToken(token, jwtService.extractUsername(token))) {
            throw new RuntimeException("Token已过期");
        }

        User user = userMapper.selectById(userId);
        log.info("[AuthService] /me 接口: 查询到的 user id={}, username={}", user != null ? user.getId() : null, user != null ? user.getUsername() : null);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        return user;
    }
    
    /**
     * 用户登出
     */
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            try {
                String tokenId = jwtService.extractTokenId(token);
                sessionService.revokeSession(tokenId);
                log.info("用户登出成功");
            } catch (Exception e) {
                log.error("登出失败: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 获取用户的活跃会话列表
     */
    public java.util.List<java.util.Map<String, Object>> getActiveSessions(Long userId) {
        return sessionService.getActiveSessions(userId);
    }
    
    /**
     * 生成认证响应
     */
    private AuthResponse generateAuthResponse(User user) {
        // ⭐ 携带角色信息生成 Token；role 为 null 时兜底为 ROLE_USER（兼容旧数据）
        String role = (user.getRole() != null) ? user.getRole() : "ROLE_USER";
        String accessToken = jwtService.generateToken(user.getId(), user.getUsername(), role);
        String refreshToken = jwtService.generateRefreshToken(user.getUsername());
        
        return new AuthResponse(
                accessToken,
                refreshToken,
                user.getId(),
                user.getUsername(),
                user.getEmail()
        );
    }
}
