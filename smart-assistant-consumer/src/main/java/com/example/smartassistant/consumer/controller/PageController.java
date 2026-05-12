/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.session.SessionManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 页面控制器（会话管理相关）
 */
@Controller
public class PageController {

    private final SessionManagementService sessionManagementService;

    public PageController(SessionManagementService sessionManagementService) {
        this.sessionManagementService = sessionManagementService;
    }

    // ⭐ 注意：/dashboard 和 /travel-plan 路由已移除
    // 前端使用 Vue SPA，不需要后端提供 HTML 模板
    // 如需监控面板，请直接访问 Prometheus: http://localhost:8082/actuator/prometheus

    /**
     * 刷新用户会话（生成新的 threadId）
     */
    @PostMapping("/api/session/refresh")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> refreshSession(@RequestBody Map<String, String> request) {
        String userId = request.get("userId");
        
        String newThreadId = sessionManagementService.refreshSession(userId);
        
        return ResponseEntity.ok(Map.of(
                "success", true,
                "threadId", newThreadId,
                "message", "会话已刷新"
        ));
    }

    /**
     * 获取当前活跃会话数
     */
    @GetMapping("/api/session/stats")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getSessionStats() {
        int activeCount = sessionManagementService.getActiveSessionCount();
        
        return ResponseEntity.ok(Map.of(
                "activeSessions", activeCount
        ));
    }

    /**
     * 语音输入测试页面
     */
    @GetMapping("/speech-test")
    public String speechTest() {
        return "speech-test";
    }
}
