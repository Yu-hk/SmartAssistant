/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.user.controller;

import com.example.smartassistant.user.model.User;
import com.example.smartassistant.user.model.dto.AuthResponse;
import com.example.smartassistant.user.model.dto.LoginRequest;
import com.example.smartassistant.user.model.dto.RegisterRequest;
import com.example.smartassistant.user.service.AuthService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证控制器
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    
    private final AuthService authService;
    
    public AuthController(AuthService authService) {
        this.authService = authService;
    }
    
    /**
     * 用户注册
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        try {
            AuthResponse response = authService.register(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("注册失败: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage() != null ? e.getMessage() : "注册失败"
            ));
        }
    }
    
    /**
     * 用户登录
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
            @RequestHeader(value = "X-Real-IP", required = false) String realIp,
            @RequestHeader(value = "User-Agent", defaultValue = "Unknown") String userAgent) {
        
        // 获取真实 IP
        String ipAddress = forwardedFor != null ? forwardedFor.split(",")[0].trim() :
                          (realIp != null ? realIp : "127.0.0.1");
        
        try {
            AuthResponse response = authService.login(request, ipAddress, userAgent);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "用户名或密码错误"
            ));
        }
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }

        String token = authHeader.substring(7);
        try {
            User user = authService.getCurrentUser(token);
            return ResponseEntity.ok(user);
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "Token无效或已过期"));
        }
    }
}
