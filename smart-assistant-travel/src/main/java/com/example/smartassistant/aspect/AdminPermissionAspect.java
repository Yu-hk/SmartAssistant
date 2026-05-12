/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.aspect;

import com.example.smartassistant.annotation.AdminOnly;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 管理员权限检查切面
 * 统一拦截所有带 @AdminOnly 注解的方法，进行权限校验
 */
@Slf4j
@Aspect
@Component
public class AdminPermissionAspect {

    private static final String ADMIN_ONLY_ERROR = "⚠️ 此操作需要管理员权限，请联系管理员或确认账号已升级为管理员身份";
    private static final String ADMIN_ROLE = "ROLE_ADMIN";

    @Around("@annotation(com.example.smartassistant.annotation.AdminOnly)")
    public Object checkAdminPermission(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String methodName = signature.getName();
        String className = signature.getDeclaringType().getSimpleName();

        // 获取 AdminOnly 注解中的描述
        AdminOnly adminOnly = signature.getMethod().getAnnotation(AdminOnly.class);
        String feature = adminOnly.value().isEmpty() ? methodName : adminOnly.value();

        // 检查权限
        if (!isAdmin()) {
            String userId = getUserId();
            log.warn("[AdminPermission] 用户 {} (角色: {}) 尝试访问管理员功能: {}.{}",
                    userId, getUserRole(), className, methodName);

            // 返回错误信息而非抛出异常，避免 Tool 调用失败
            return ADMIN_ONLY_ERROR;
        }

        log.info("[AdminPermission] 管理员权限校验通过: {}.{}", className, methodName);
        return joinPoint.proceed();
    }

    /**
     * 检查当前请求是否为管理员
     */
    private boolean isAdmin() {
        String userRole = getUserRole();
        return ADMIN_ROLE.equalsIgnoreCase(userRole);
    }

    /**
     * 获取当前用户角色
     */
    private String getUserRole() {
        try {
            HttpServletRequest request = getRequest();
            if (request != null) {
                return request.getHeader("X-User-Role");
            }
        } catch (Exception e) {
            log.debug("无法获取用户角色: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 获取当前用户ID
     */
    private String getUserId() {
        try {
            HttpServletRequest request = getRequest();
            if (request != null) {
                return request.getHeader("X-User-Id");
            }
        } catch (Exception e) {
            log.debug("无法获取用户ID: {}", e.getMessage());
        }
        return "unknown";
    }

    /**
     * 获取当前 HTTP 请求
     */
    private HttpServletRequest getRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes != null ? attributes.getRequest() : null;
    }
}
