/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.common.db.PermissionBridgeService;
import com.example.smartassistant.consumer.security.AuthenticatedUser;
import com.example.smartassistant.consumer.service.admin.AdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
    private final PermissionBridgeService permissionBridgeService;

    public AdminController(AdminService adminService,
                           @Autowired(required = false) PermissionBridgeService permissionBridgeService) {
        this.adminService = adminService;
        this.permissionBridgeService = permissionBridgeService;
    }

    // ==================== 统计 ====================

    /**
     * 管理后台数据总览。
     * GET /api/stats → 前端 AdminPage.OverviewTab
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getStats(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader) {
        requireAdmin(userIdHeader, roleHeader);
        return ResponseEntity.ok(adminService.getStats());
    }

    // ==================== 会话管理 ====================

    /**
     * 会话列表。
     * GET /api/sessions → 前端 SessionsTab
     */
    @GetMapping("/sessions")
    public ResponseEntity<?> getSessions(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader) {
        AuthenticatedUser user = AuthenticatedUser.require(userIdHeader, null, roleHeader);
        return ResponseEntity.ok(adminService.getSessions(user.userId(), user.isAdmin()));
    }

    /**
     * 会话详情。
     * GET /api/sessions/{id}
     */
    @GetMapping("/sessions/{id}")
    public ResponseEntity<?> getSession(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader) {
        AuthenticatedUser user = AuthenticatedUser.require(userIdHeader, null, roleHeader);
        Map<String, Object> detail = adminService.getSessionDetail(id, user.userId(), user.isAdmin());
        return detail.containsKey("error") ? ResponseEntity.notFound().build() : ResponseEntity.ok(detail);
    }

    /**
     * 删除会话。
     * DELETE /api/sessions/{id}
     */
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<?> deleteSession(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader) {
        AuthenticatedUser user = AuthenticatedUser.require(userIdHeader, null, roleHeader);
        boolean deleted = adminService.deleteSession(id, user.userId(), user.isAdmin());
        return deleted ? ResponseEntity.ok(Map.of("success", true)) : ResponseEntity.notFound().build();
    }

    /**
     * 主动结束会话（无需评分）。
     * POST /api/sessions/{id}/close
     */
    @PostMapping("/sessions/{id}/close")
    public ResponseEntity<?> closeSession(@PathVariable String id,
                                          @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
                                          @RequestHeader(value = "X-User-Role", required = false) String roleHeader) {
        AuthenticatedUser user = AuthenticatedUser.require(userIdHeader, null, roleHeader);
        boolean closed = adminService.closeSession(id, user.userId(), user.isAdmin());
        return closed
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }

    /**
     * 提交满意度并结束会话。
     * POST /api/sessions/{id}/satisfaction
     */
    @PostMapping("/sessions/{id}/satisfaction")
    public ResponseEntity<?> submitSatisfaction(@PathVariable String id,
                                                 @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
                                                 @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
                                                 @RequestBody Map<String, Object> body) {
        Object rawScore = body.get("score");
        if (!(rawScore instanceof Number number) || number.intValue() < 1 || number.intValue() > 5) {
            return ResponseEntity.badRequest().body(Map.of("error", "评分必须在 1-5 之间"));
        }
        AuthenticatedUser user = AuthenticatedUser.require(userIdHeader, null, roleHeader);
        String comment = body.get("comment") == null ? null : String.valueOf(body.get("comment"));
        boolean saved = adminService.submitSatisfaction(
                id, user.userId(), user.isAdmin(), number.intValue(), comment);
        return saved
                ? ResponseEntity.ok(Map.of("success", true, "message", "感谢您的评价！"))
                : ResponseEntity.notFound().build();
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
    public ResponseEntity<?> createFaq(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestBody Map<String, String> body) {
        requireAdmin(userIdHeader, roleHeader);
        return ResponseEntity.ok(adminService.createFaq(body));
    }

    /**
     * 更新 FAQ。
     * PUT /api/faq/{id}
     */
    @PutMapping("/faq/{id}")
    public ResponseEntity<?> updateFaq(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestBody Map<String, String> body) {
        requireAdmin(userIdHeader, roleHeader);
        var result = adminService.updateFaq(id, body);
        if (result == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(result);
    }

    /**
     * 删除 FAQ。
     * DELETE /api/faq/{id}
     */
    @DeleteMapping("/faq/{id}")
    public ResponseEntity<?> deleteFaq(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader) {
        requireAdmin(userIdHeader, roleHeader);
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
    public ResponseEntity<?> checkLogin(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Username", required = false) String usernameHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader) {
        AuthenticatedUser user = AuthenticatedUser.require(userIdHeader, usernameHeader, roleHeader);
        return ResponseEntity.ok(Map.of(
                "loggedIn", true,
                "userId", user.userId(),
                "username", user.username() == null ? "" : user.username(),
                "role", user.role()));
    }

    /**
     * 保存环境变量配置（简化版，仅记录）。
     * POST /api/save-env-config
     */
    @PostMapping("/save-env-config")
    public ResponseEntity<?> saveEnvConfig(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestBody Map<String, Object> config) {
        requireAdmin(userIdHeader, roleHeader);
        log.info("[Admin] 保存环境配置: keys={}", config.keySet());
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ==================== 权限响应（前后端审批打通） ====================

    /**
     * 前端权限弹窗的用户响应。
     * POST /api/permission-response
     *
     * <p>前端 InlinePermissionCard 点击"允许"/"拒绝"时调用此接口。
     * Agent 端通过 {@link PermissionBridgeService#waitForResponse(String)} 阻塞等待。
     */
    @PostMapping("/permission-response")
    public ResponseEntity<?> permissionResponse(@RequestBody Map<String, String> body) {
        String requestId = body.get("requestId");
        String behavior = body.getOrDefault("behavior", body.get("action"));
        if (requestId == null || behavior == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 requestId 或 behavior"));
        }
        if (permissionBridgeService == null) {
            return ResponseEntity.ok(Map.of("success", false, "error", "权限桥接服务未启用"));
        }
        boolean success = permissionBridgeService.respondToRequest(requestId, behavior);
        if (!success) {
            return ResponseEntity.ok(Map.of("success", false, "error", "请求不存在或已超时"));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ==================== 成本看板 ====================

    /**
     * 成本统计数据。
     * GET /api/admin/costs
     */
    @GetMapping("/admin/costs")
    public ResponseEntity<?> getCosts(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader) {
        requireAdmin(userIdHeader, roleHeader);
        return ResponseEntity.ok(adminService.getCosts());
    }

    private static AuthenticatedUser requireAdmin(String userIdHeader, String roleHeader) {
        AuthenticatedUser user = AuthenticatedUser.require(userIdHeader, null, roleHeader);
        if (!user.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator role required");
        }
        return user;
    }
}
