/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.security.AuthenticatedUser;
import com.example.smartassistant.consumer.service.insight.CustomerProfileVO;
import com.example.smartassistant.consumer.service.insight.SessionInsightService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 实时会话洞察 API。
 *
 * <p>为前端「实时会话洞察」右栏提供后端能力：</p>
 * <ul>
 *     <li>{@code POST /api/insight/emotion} — 情绪分析（前端传入最新用户文本）；</li>
 *     <li>{@code GET  /api/insight/kb-search} — 知识库检索（基于 faq 表）；</li>
 *     <li>{@code POST /api/insight/ticket} — 创建工单（持久化）；</li>
 *     <li>{@code POST /api/insight/ticket/status} — 推进工单状态（生命周期）；</li>
 *     <li>{@code POST /api/insight/ticket/close} — 关闭工单（终态，可附结论）；</li>
 *     <li>{@code GET  /api/insight/tickets} — 工单列表（按 sessionId / customerName 过滤）；</li>
 *     <li>{@code GET  /api/insight/profile} — 客户 360° 画像（聚合偏好/事实/记忆/情绪）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/insight")
public class InsightController {

    private static final Logger log = LoggerFactory.getLogger(InsightController.class);

    private final SessionInsightService insightService;

    public InsightController(SessionInsightService insightService) {
        this.insightService = insightService;
    }

    /**
     * 情绪分析。
     * POST /api/insight/emotion  Body: {"text": "...", "userId": 123}
     */
    @PostMapping("/emotion")
    public ResponseEntity<Map<String, Object>> analyzeEmotion(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestBody(required = false) Map<String, Object> body) {
        String text = (body == null) ? "" : (String) body.getOrDefault("text", "");
        Long userId = AuthenticatedUser.require(userIdHeader, null, roleHeader).userId();
        String triggerTopic = (body == null) ? null : (String) body.get("triggerTopic");
        SessionInsightService.EmotionResult r = insightService.analyzeEmotion(text, userId, triggerTopic);
        return ResponseEntity.ok(Map.of(
                "label", r.label(),
                "score", r.score(),
                "confidence", r.confidence()
        ));
    }

    /**
     * 知识库检索。
     * GET /api/insight/kb-search?query=...&intent=...
     */
    @GetMapping("/kb-search")
    public ResponseEntity<Map<String, Object>> searchKb(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "intent", required = false) String intent) {
        AuthenticatedUser.require(userIdHeader, null, roleHeader);
        List<SessionInsightService.KbHit> hits = insightService.searchKb(query, intent);
        return ResponseEntity.ok(Map.of("hits", hits, "count", hits.size()));
    }

    /**
     * 创建工单。
     * POST /api/insight/ticket  Body: {"sessionId","intent","summary","customerName"}
     */
    @PostMapping("/ticket")
    public ResponseEntity<Map<String, Object>> createTicket(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestBody Map<String, String> body) {
        AuthenticatedUser user = AuthenticatedUser.require(userIdHeader, null, roleHeader);
        String sessionId = body.getOrDefault("sessionId", "");
        String intent = body.getOrDefault("intent", "unknown");
        String summary = body.getOrDefault("summary", "");
        String customerName = body.getOrDefault("customerName", "");
        try {
            SessionInsightService.TicketView t = insightService.createTicket(
                    user.userId(), sessionId, intent, summary, customerName);
            return ResponseEntity.ok(Map.of("id", t.id(), "status", t.status()));
        } catch (Exception e) {
            log.warn("[Insight] 工单创建返回失败态: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("error", e.getMessage(), "status", "FAILED"));
        }
    }

    /**
     * 推进工单状态（生命周期）。
     * POST /api/insight/ticket/status  Body: {"ticketId","status"}
     */
    @PostMapping("/ticket/status")
    public ResponseEntity<Map<String, Object>> updateTicketStatus(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestBody Map<String, String> body) {
        AuthenticatedUser user = AuthenticatedUser.require(userIdHeader, null, roleHeader);
        String ticketId = body.get("ticketId");
        String status = body.get("status");
        try {
            SessionInsightService.TicketView t = insightService.updateTicketStatus(
                    ticketId, user.userId(), user.isAdmin(), status);
            return ResponseEntity.ok(Map.of("id", t.id(), "status", t.status(), "updatedAt", t.updatedAt()));
        } catch (Exception e) {
            log.warn("[Insight] 工单状态更新失败: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("error", e.getMessage(), "status", "FAILED"));
        }
    }

    /**
     * 关闭工单（终态，可附处理结论）。
     * POST /api/insight/ticket/close  Body: {"ticketId","resolution"}
     */
    @PostMapping("/ticket/close")
    public ResponseEntity<Map<String, Object>> closeTicket(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestBody Map<String, String> body) {
        AuthenticatedUser user = AuthenticatedUser.require(userIdHeader, null, roleHeader);
        String ticketId = body.get("ticketId");
        String resolution = body.getOrDefault("resolution", "");
        try {
            SessionInsightService.TicketView t = insightService.closeTicket(
                    ticketId, user.userId(), user.isAdmin(), resolution);
            return ResponseEntity.ok(Map.of("id", t.id(), "status", t.status(), "closedAt", t.closedAt()));
        } catch (Exception e) {
            log.warn("[Insight] 工单关闭失败: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("error", e.getMessage(), "status", "FAILED"));
        }
    }

    /**
     * 工单列表（前端面板展示）。
     * GET /api/insight/tickets?sessionId=&customerName=
     */
    @GetMapping("/tickets")
    public ResponseEntity<List<SessionInsightService.TicketView>> listTickets(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "customerName", required = false) String customerName) {
        AuthenticatedUser user = AuthenticatedUser.require(userIdHeader, null, roleHeader);
        List<SessionInsightService.TicketView> tickets = insightService.listTickets(
                user.userId(), user.isAdmin(), sessionId, customerName);
        return ResponseEntity.ok(tickets);
    }

    /**
     * 客户 360° 画像。
     * GET /api/insight/profile?userId=123&userName=xxx
     */
    @GetMapping("/profile")
    public ResponseEntity<CustomerProfileVO> getProfile(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Username", required = false) String usernameHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "userName", required = false) String userName) {
        AuthenticatedUser user = AuthenticatedUser.require(userIdHeader, usernameHeader, roleHeader);
        Long targetUserId = user.isAdmin() && userId != null ? userId : user.userId();
        String targetUserName = user.isAdmin() && userName != null ? userName : user.username();
        CustomerProfileVO profile = insightService.getCustomerProfile(targetUserId, targetUserName);
        return ResponseEntity.ok(profile);
    }
}
