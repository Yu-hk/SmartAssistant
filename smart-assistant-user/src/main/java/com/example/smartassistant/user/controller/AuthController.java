/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.user.controller;

import com.example.smartassistant.common.exception.ServiceException;
import com.example.smartassistant.common.response.ApiResponse;
import com.example.smartassistant.user.model.dto.AuthResponse;
import com.example.smartassistant.user.model.dto.CurrentUserResponse;
import com.example.smartassistant.user.model.dto.LoginRequest;
import com.example.smartassistant.user.model.dto.LogoutRequest;
import com.example.smartassistant.user.model.dto.RefreshTokenRequest;
import com.example.smartassistant.user.model.dto.RegisterRequest;
import com.example.smartassistant.user.service.AuthService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public authentication endpoints and authenticated account endpoints. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
            @RequestHeader(value = "X-Real-IP", required = false) String realIp,
            @RequestHeader(value = "User-Agent", defaultValue = "Unknown") String userAgent) {
        AuthResponse response = authService.register(request, resolveIp(forwardedFor, realIp), userAgent);
        log.info("User registered: username={}", request.getUsername());
        return ApiResponse.success(response);
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
            @RequestHeader(value = "X-Real-IP", required = false) String realIp,
            @RequestHeader(value = "User-Agent", defaultValue = "Unknown") String userAgent) {
        AuthResponse response = authService.login(
                request,
                resolveIp(forwardedFor, realIp),
                userAgent);
        log.info("User logged in: username={}", request.getUsername());
        return ApiResponse.success(response);
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(
            @Valid @RequestBody RefreshTokenRequest request,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
            @RequestHeader(value = "X-Real-IP", required = false) String realIp,
            @RequestHeader(value = "User-Agent", defaultValue = "Unknown") String userAgent) {
        return ApiResponse.success(authService.refreshToken(
                request.getRefreshToken(),
                resolveIp(forwardedFor, realIp),
                userAgent));
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> getCurrentUser(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return ApiResponse.success(authService.getCurrentUser(extractBearerToken(authHeader)));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) LogoutRequest request) {
        authService.logout(
                extractBearerToken(authHeader),
                request == null ? null : request.refreshToken());
        return ApiResponse.success();
    }

    private String extractBearerToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw ServiceException.unauthorized("未登录");
        }
        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            throw ServiceException.unauthorized("未登录");
        }
        return token;
    }

    private String resolveIp(String forwardedFor, String realIp) {
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return realIp == null || realIp.isBlank() ? "127.0.0.1" : realIp;
    }
}
