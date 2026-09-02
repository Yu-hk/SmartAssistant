/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.common.db.PermissionBridgeService;
import com.example.smartassistant.consumer.service.admin.AdminService;
import com.example.smartassistant.consumer.service.session.ConversationGateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Administration APIs plus user-scoped conversation compatibility endpoints.
 *
 * <p>Every {@code /api/admin/**} handler validates the exact gateway role again
 * as defense in depth. Global session access is never available from
 * {@code /api/sessions}, including for an administrator.</p>
 */
@RestController
@RequestMapping("/api")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final String ADMIN_ROLE = "ROLE_ADMIN";

    private final AdminService adminService;
    private final PermissionBridgeService permissionBridgeService;

    @Autowired(required = false)
    private ConversationGateService conversationGateService;

    public AdminController(
            AdminService adminService,
            @Autowired(required = false) PermissionBridgeService permissionBridgeService) {
        this.adminService = adminService;
        this.permissionBridgeService = permissionBridgeService;
    }

    // ==================== New admin contract ====================

    @GetMapping("/admin/stats")
    public ResponseEntity<?> getAdminStats(
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        if (!isAdmin(role)) return forbidden();
        return ResponseEntity.ok(adminService.getStats());
    }

    @GetMapping("/admin/sessions")
    public ResponseEntity<?> getAdminSessions(
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String intent,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (!isAdmin(role)) return forbidden();
        return ResponseEntity.ok(
                adminService.searchAdminSessions(query, userId, status, intent, page, size));
    }

    @GetMapping("/admin/sessions/{id}")
    public ResponseEntity<?> getAdminSession(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestParam(required = false) Long userId) {
        if (!isAdmin(role)) return forbidden();
        return adminService.getAdminSessionDetail(id, userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/admin/sessions/{id}")
    public ResponseEntity<?> deleteAdminSession(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestParam(required = false) Long userId) {
        if (!isAdmin(role)) return forbidden();
        return adminService.deleteAdminSession(id, userId)
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/admin/faqs")
    public ResponseEntity<?> getAdminFaqs(
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        if (!isAdmin(role)) return forbidden();
        return ResponseEntity.ok(adminService.getFaqs());
    }

    @PostMapping("/admin/faqs")
    public ResponseEntity<?> createAdminFaq(
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestBody Map<String, String> body) {
        if (!isAdmin(role)) return forbidden();
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(adminService.createFaq(body));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @PostMapping("/admin/faqs/import")
    public ResponseEntity<?> importAdminFaqs(
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestBody FaqImportRequest request) {
        if (!isAdmin(role)) return forbidden();
        try {
            return ResponseEntity.ok(adminService.importFaqs(
                    request.sourceName(),
                    request.sourceType(),
                    request.overwrite(),
                    request.items()));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @PutMapping("/admin/faqs/{id}")
    public ResponseEntity<?> updateAdminFaq(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestBody Map<String, String> body) {
        if (!isAdmin(role)) return forbidden();
        try {
            var result = adminService.updateFaq(id, body);
            return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/admin/faqs/{id}")
    public ResponseEntity<?> deleteAdminFaq(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        if (!isAdmin(role)) return forbidden();
        return adminService.deleteFaq(id)
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/admin/faqs/{id}/hit")
    public ResponseEntity<?> hitAdminFaq(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        if (!isAdmin(role)) return forbidden();
        return adminService.hitFaq(id)
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/admin/costs")
    public ResponseEntity<?> getCosts(
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        if (!isAdmin(role)) return forbidden();
        return ResponseEntity.ok(adminService.getCosts());
    }

    // ==================== User-scoped sessions ====================

    @GetMapping("/sessions")
    public ResponseEntity<?> getSessions(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-User-Role", required = false) String ignoredRole) {
        return ResponseEntity.ok(adminService.getSessions(userId));
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<?> getSession(
            @PathVariable String id,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-User-Role", required = false) String ignoredRole) {
        return adminService.getUserSessionDetail(id, userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<?> deleteSession(
            @PathVariable String id,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-User-Role", required = false) String ignoredRole) {
        return adminService.deleteUserSession(id, userId)
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/sessions/{id}/satisfaction")
    public ResponseEntity<?> saveSatisfaction(
            @PathVariable String id,
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody Map<String, Object> body) {
        Object rawRating = body.containsKey("rating") ? body.get("rating") : body.get("score");
        if (!(rawRating instanceof Number number)) {
            return badRequest("rating is required and must be a number between 1 and 5");
        }
        String comment = firstString(body, "comment", "satisfactionComment", "feedbackText");
        try {
            return adminService.saveSatisfaction(id, userId, number.intValue(), comment)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @PostMapping("/sessions/{id}/close")
    public ResponseEntity<?> closeSession(
            @PathVariable String id,
            @RequestHeader("X-User-Id") Long userId) {
        ConversationGateService.CloseDecision gateDecision = conversationGateService == null
                ? null : conversationGateService.close(userId.toString(), id);
        if (gateDecision != null && gateDecision.status() == ConversationGateService.CloseStatus.BUSY) {
            return ResponseEntity.status(409).body(Map.of(
                    "success", false,
                    "status", "BUSY",
                    "message", "当前对话仍有请求正在处理，请先停止生成"));
        }
        if (gateDecision != null && gateDecision.status() == ConversationGateService.CloseStatus.UNAVAILABLE) {
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "status", "UNAVAILABLE",
                    "message", "会话状态服务暂不可用"));
        }
        return adminService.closeSession(id, userId)
                ? ResponseEntity.ok(Map.of(
                        "success", true,
                        "status", "CLOSED"))
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/sessions/{id}/resume")
    public ResponseEntity<?> resumeSession(
            @PathVariable String id,
            @RequestHeader("X-User-Id") Long userId) {
        if (conversationGateService == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "status", "UNAVAILABLE",
                    "message", "会话状态服务暂不可用"));
        }
        ConversationGateService.ResumeDecision decision =
                conversationGateService.resume(userId.toString(), id);
        return switch (decision.status()) {
            case RESUMED, ALREADY_ACTIVE -> ResponseEntity.ok(Map.of(
                    "success", true,
                    "status", "ACTIVE",
                    "sessionId", id));
            case CONFLICT -> ResponseEntity.status(409).body(Map.of(
                    "success", false,
                    "status", "CONFLICT",
                    "activeSessionId", decision.activeSessionId() == null ? "" : decision.activeSessionId(),
                    "message", "已有进行中的会话，请先结束后再恢复"));
            case NOT_SUSPENDED -> ResponseEntity.status(409).body(Map.of(
                    "success", false,
                    "status", "NOT_SUSPENDED",
                    "message", "该会话当前不可恢复"));
            case UNAVAILABLE -> ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "status", "UNAVAILABLE",
                    "message", "会话状态服务暂不可用"));
        };
    }

    // ==================== Protected compatibility aliases ====================

    @GetMapping("/stats")
    public ResponseEntity<?> getStats(
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        return getAdminStats(role);
    }

    @GetMapping("/faq")
    public ResponseEntity<?> getFaqs(
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        return getAdminFaqs(role);
    }

    @PostMapping("/faq")
    public ResponseEntity<?> createFaq(
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestBody Map<String, String> body) {
        if (!isAdmin(role)) return forbidden();
        try {
            return ResponseEntity.ok(adminService.createFaq(body));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @PutMapping("/faq/{id}")
    public ResponseEntity<?> updateFaq(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestBody Map<String, String> body) {
        return updateAdminFaq(id, role, body);
    }

    @DeleteMapping("/faq/{id}")
    public ResponseEntity<?> deleteFaq(
            @PathVariable String id,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        return deleteAdminFaq(id, role);
    }

    @PostMapping("/faq/{id}/hit")
    public ResponseEntity<?> hitFaq(
            @PathVariable String id,
            @RequestHeader("X-User-Id") Long ignoredUserId) {
        return adminService.hitFaq(id)
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/check-login")
    public ResponseEntity<?> checkLogin(
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        if (!isAdmin(role)) return forbidden();
        return ResponseEntity.ok(Map.of("loggedIn", true, "role", ADMIN_ROLE));
    }

    @PostMapping("/save-env-config")
    public ResponseEntity<?> saveEnvConfig(
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestBody Map<String, Object> config) {
        if (!isAdmin(role)) return forbidden();
        log.info("[Admin] Environment configuration received: keys={}", config.keySet());
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ==================== User permission bridge ====================

    @PostMapping("/permission-response")
    public ResponseEntity<?> permissionResponse(@RequestBody Map<String, String> body) {
        String requestId = body.get("requestId");
        String behavior = body.getOrDefault("behavior", body.get("action"));
        if (requestId == null || behavior == null) {
            return badRequest("requestId and behavior are required");
        }
        if (permissionBridgeService == null) {
            return ResponseEntity.ok(Map.of(
                    "success", false, "error", "permission bridge is not enabled"));
        }
        boolean success = permissionBridgeService.respondToRequest(requestId, behavior);
        return success
                ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.ok(Map.of(
                        "success", false, "error", "request does not exist or has expired"));
    }

    private boolean isAdmin(String role) {
        return ADMIN_ROLE.equals(role);
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "Forbidden",
                "message", "Administrator privileges are required"));
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Bad Request",
                "message", message));
    }

    private static String firstString(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            Object value = body.get(key);
            if (value != null) return value.toString();
        }
        return "";
    }

    public record FaqImportRequest(
            String sourceName,
            String sourceType,
            boolean overwrite,
            List<Map<String, String>> items) {}
}
