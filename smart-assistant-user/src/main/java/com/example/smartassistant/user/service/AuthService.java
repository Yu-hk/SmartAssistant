/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.user.service;

import com.example.smartassistant.common.exception.ServiceException;
import com.example.smartassistant.user.mapper.UserMapper;
import com.example.smartassistant.user.model.User;
import com.example.smartassistant.user.model.dto.AuthResponse;
import com.example.smartassistant.user.model.dto.CurrentUserResponse;
import com.example.smartassistant.user.model.dto.LoginRequest;
import com.example.smartassistant.user.model.dto.RegisterRequest;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authentication, token rotation and logout orchestration. */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String DEFAULT_ROLE = "ROLE_USER";

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

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        return register(request, "127.0.0.1", "Unknown");
    }

    @Transactional
    public AuthResponse register(RegisterRequest request, String ipAddress, String userAgent) {
        if (userMapper.existsByUsername(request.getUsername())) {
            throw duplicateUsername();
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setRole(DEFAULT_ROLE);

        try {
            userMapper.insert(user);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateUsername();
        }

        AuthResponse response = generateAuthResponse(user);
        createAccessSession(user, response, ipAddress, userAgent);
        log.info("User registered successfully: username={}", request.getUsername());
        return response;
    }

    public AuthResponse login(LoginRequest request, String ipAddress, String userAgent) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (AuthenticationException exception) {
            throw ServiceException.unauthorized("用户名或密码错误");
        }

        User user = userMapper.findByUsername(request.getUsername());
        if (user == null) {
            throw ServiceException.unauthorized("用户名或密码错误");
        }

        AuthResponse response = generateAuthResponse(user);
        createAccessSession(user, response, ipAddress, userAgent);
        log.info("User logged in successfully: username={}", request.getUsername());
        return response;
    }

    public AuthResponse refreshToken(String refreshToken) {
        return refreshToken(refreshToken, "127.0.0.1", "Unknown");
    }

    public AuthResponse refreshToken(String refreshToken, String ipAddress, String userAgent) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw invalidRefreshToken();
        }

        try {
            String username = jwtService.extractUsername(refreshToken);
            if (!jwtService.validateRefreshToken(refreshToken, username)) {
                throw invalidRefreshToken();
            }

            String refreshTokenId = jwtService.extractTokenId(refreshToken);
            if (refreshTokenId == null
                    || refreshTokenId.isBlank()
                    || !sessionService.consumeRefreshToken(
                            refreshTokenId,
                            jwtService.getRefreshBlacklistTtl())) {
                throw invalidRefreshToken();
            }

            User user = userMapper.findByUsername(username);
            if (user == null) {
                throw invalidRefreshToken();
            }

            AuthResponse response = generateAuthResponse(user);
            createAccessSession(user, response, ipAddress, userAgent);
            return response;
        } catch (ServiceException exception) {
            throw exception;
        } catch (JwtException | IllegalArgumentException exception) {
            throw invalidRefreshToken();
        }
    }

    public CurrentUserResponse getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw ServiceException.unauthorized("未登录");
        }

        User user = userMapper.findByUsername(authentication.getName());
        if (user == null) {
            throw ServiceException.unauthorized("登录用户不存在");
        }
        return toCurrentUserResponse(user);
    }

    public CurrentUserResponse getCurrentUser(String token) {
        if (token == null || token.isBlank()) {
            throw ServiceException.unauthorized("未登录");
        }

        try {
            String username = jwtService.extractUsername(token);
            if (!jwtService.validateAccessToken(token, username)) {
                throw ServiceException.unauthorized("访问令牌无效或已过期");
            }

            String tokenId = jwtService.extractTokenId(token);
            if (tokenId == null || tokenId.isBlank() || !sessionService.validateSession(tokenId)) {
                throw ServiceException.unauthorized("登录会话已失效");
            }

            Long userId = jwtService.extractUserId(token);
            User user = userId == null ? null : userMapper.selectById(userId);
            if (user == null) {
                throw ServiceException.unauthorized("登录用户不存在");
            }
            return toCurrentUserResponse(user);
        } catch (ServiceException exception) {
            throw exception;
        } catch (JwtException | IllegalArgumentException exception) {
            throw ServiceException.unauthorized("访问令牌无效或已过期");
        }
    }

    public void logout(String token) {
        logout(token, null);
    }

    public void logout(String token, String refreshToken) {
        if (token == null || token.isBlank()) {
            throw ServiceException.unauthorized("未登录");
        }

        try {
            String username = jwtService.extractUsername(token);
            if (!jwtService.validateAccessToken(token, username)) {
                throw ServiceException.unauthorized("访问令牌无效或已过期");
            }

            String tokenId = jwtService.extractTokenId(token);
            if (tokenId == null || tokenId.isBlank()) {
                throw ServiceException.unauthorized("访问令牌无效");
            }

            String refreshTokenId = null;
            if (refreshToken != null && !refreshToken.isBlank()) {
                String refreshUsername = jwtService.extractUsername(refreshToken);
                if (!username.equals(refreshUsername)
                        || !jwtService.validateRefreshToken(refreshToken, refreshUsername)) {
                    throw invalidRefreshToken();
                }
                refreshTokenId = jwtService.extractTokenId(refreshToken);
                if (refreshTokenId == null || refreshTokenId.isBlank()) {
                    throw invalidRefreshToken();
                }
            }

            if (refreshTokenId != null) {
                sessionService.blacklistRefreshToken(
                        refreshTokenId,
                        jwtService.getRefreshBlacklistTtl());
            }
            sessionService.revokeAccessToken(tokenId, jwtService.getRemainingValidity(token));
            SecurityContextHolder.clearContext();
            log.info("User logged out successfully: username={}", username);
        } catch (ServiceException exception) {
            throw exception;
        } catch (JwtException | IllegalArgumentException exception) {
            throw ServiceException.unauthorized("访问令牌无效或已过期");
        }
    }

    public java.util.List<java.util.Map<String, Object>> getActiveSessions(Long userId) {
        return sessionService.getActiveSessions(userId);
    }

    private AuthResponse generateAuthResponse(User user) {
        String role = normalizeRole(user.getRole());
        String accessToken = jwtService.generateToken(user.getId(), user.getUsername(), role);
        String refreshToken = jwtService.generateRefreshToken(user.getUsername());

        return new AuthResponse(
                accessToken,
                refreshToken,
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                role);
    }

    private void createAccessSession(
            User user,
            AuthResponse response,
            String ipAddress,
            String userAgent) {
        String tokenId = jwtService.extractTokenId(response.getToken());
        sessionService.createSession(user.getId(), tokenId, ipAddress, userAgent);
    }

    private CurrentUserResponse toCurrentUserResponse(User user) {
        return new CurrentUserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                normalizeRole(user.getRole()));
    }

    private String normalizeRole(String role) {
        return role == null || role.isBlank() ? DEFAULT_ROLE : role;
    }

    private ServiceException duplicateUsername() {
        return new ServiceException(409, "USERNAME_CONFLICT", "用户名已存在");
    }

    private ServiceException invalidRefreshToken() {
        return new ServiceException(401, "INVALID_REFRESH_TOKEN", "刷新令牌无效或已过期");
    }
}
