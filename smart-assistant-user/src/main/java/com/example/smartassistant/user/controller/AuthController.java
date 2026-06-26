package com.example.smartassistant.user.controller;

import com.example.smartassistant.common.exception.ServiceException;
import com.example.smartassistant.common.response.ApiResponse;
import com.example.smartassistant.user.model.User;
import com.example.smartassistant.user.model.dto.AuthResponse;
import com.example.smartassistant.user.model.dto.LoginRequest;
import com.example.smartassistant.user.model.dto.RegisterRequest;
import com.example.smartassistant.user.service.AuthService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器
 * <p>
 * 统一返回 {@link ApiResponse} 格式。
 * 业务异常通过 {@link ServiceException} 抛给 GlobalExceptionHandler 统一处理。
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
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        log.info("用户注册成功: username={}", request.getUsername());
        return ApiResponse.success(response);
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
            @RequestHeader(value = "X-Real-IP", required = false) String realIp,
            @RequestHeader(value = "User-Agent", defaultValue = "Unknown") String userAgent) {

        String ipAddress = forwardedFor != null ? forwardedFor.split(",")[0].trim() :
                          (realIp != null ? realIp : "127.0.0.1");

        AuthResponse response = authService.login(request, ipAddress, userAgent);
        log.info("用户登录成功: username={}", request.getUsername());
        return ApiResponse.success(response);
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/me")
    public ApiResponse<User> getCurrentUser(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw ServiceException.unauthorized("未登录");
        }

        String token = authHeader.substring(7);
        User user = authService.getCurrentUser(token);
        return ApiResponse.success(user);
    }
}
