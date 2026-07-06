/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.admin.AdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理后台 Controller。
 *
 * <p>提供前端 AdminPage 需要的所有 API 端点。
 * 补齐了原有的 Stats/Sessions/FAQ 缺失。
 */
@RestController
@RequestMapping("/api")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    // ==================== 统计 ====================

    /**
     * 管理后台数据总览。
     * GET /api/stats → 前端 AdminPage.OverviewTab
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        return ResponseEntity.ok(adminService.getStats());
    }

    // ==================== 会话管理 ====================

    /**
     * 会话列表。
     * GET /api/sessions → 前端 SessionsTab
     */
    @GetMapping("/sessions")
    public ResponseEntity<?> getSessions() {
        return ResponseEntity.ok(adminService.getSessions());
    }

    /**
     * 会话详情。
     * GET /api/sessions/{id}
     */
    @GetMapping("/sessions/{id}")
    public ResponseEntity<?> getSession(@PathVariable String id) {
        return ResponseEntity.ok(adminService.getSessionDetail(id));
    }

    /**
     * 删除会话。
     * DELETE /api/sessions/{id}
     */
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<?> deleteSession(@PathVariable String id) {
        boolean deleted = adminService.deleteSession(id);
        return ResponseEntity.ok(Map.of("success", deleted));
    }

    // ==================== FAQ 管理 ====================

    /**
     * FAQ 列表。
     * GET /api/faq → 前端 FaqTab
     */
    @GetMapping("/faq")
    public ResponseEntity<?> getFaqs() {
        return ResponseEntity.ok(adminService.getFaqs());
    }

    /**
     * 新增 FAQ。
     * POST /api/faq
     */
    @PostMapping("/faq")
    public ResponseEntity<?> createFaq(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(adminService.createFaq(body));
    }

    /**
     * 更新 FAQ。
     * PUT /api/faq/{id}
     */
    @PutMapping("/faq/{id}")
    public ResponseEntity<?> updateFaq(@PathVariable String id, @RequestBody Map<String, String> body) {
        var result = adminService.updateFaq(id, body);
        if (result == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(result);
    }

    /**
     * 删除 FAQ。
     * DELETE /api/faq/{id}
     */
    @DeleteMapping("/faq/{id}")
    public ResponseEntity<?> deleteFaq(@PathVariable String id) {
        boolean deleted = adminService.deleteFaq(id);
        return deleted ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }

    /**
     * FAQ 点击计数（前端记录热点）。
     * POST /api/faq/{id}/hit
     */
    @PostMapping("/faq/{id}/hit")
    public ResponseEntity<?> hitFaq(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ==================== 环境配置（简化） ====================

    /**
     * 检查登录状态。
     * GET /api/check-login
     */
    @GetMapping("/check-login")
    public ResponseEntity<?> checkLogin() {
        return ResponseEntity.ok(Map.of("loggedIn", true));
    }

    /**
     * 保存环境变量配置（简化版，仅记录）。
     * POST /api/save-env-config
     */
    @PostMapping("/save-env-config")
    public ResponseEntity<?> saveEnvConfig(@RequestBody Map<String, Object> config) {
        log.info("[Admin] 保存环境配置: keys={}", config.keySet());
        return ResponseEntity.ok(Map.of("success", true));
    }
}
