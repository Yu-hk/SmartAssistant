/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.insight.SessionInsightService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 实时会话洞察 API。
 *
 * <p>为前端「实时会话洞察」右栏提供三类后端能力：</p>
 * <ul>
 *     <li>{@code POST /api/insight/emotion} — 情绪分析（前端传入最新用户文本）；</li>
 *     <li>{@code GET  /api/insight/kb-search} — 知识库检索（基于 faq 表）；</li>
 *     <li>{@code POST /api/insight/ticket} — 创建工单（持久化）。</li>
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
     * POST /api/insight/emotion  Body: {"text": "..."}
     */
    @PostMapping("/emotion")
    public ResponseEntity<Map<String, Object>> analyzeEmotion(@RequestBody(required = false) Map<String, String> body) {
        String text = (body == null) ? "" : body.getOrDefault("text", "");
        SessionInsightService.EmotionResult r = insightService.analyzeEmotion(text);
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
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "intent", required = false) String intent) {
        List<SessionInsightService.KbHit> hits = insightService.searchKb(query, intent);
        return ResponseEntity.ok(Map.of("hits", hits, "count", hits.size()));
    }

    /**
     * 创建工单。
     * POST /api/insight/ticket  Body: {"sessionId","intent","summary","customerName"}
     */
    @PostMapping("/ticket")
    public ResponseEntity<Map<String, Object>> createTicket(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("sessionId", "");
        String intent = body.getOrDefault("intent", "unknown");
        String summary = body.getOrDefault("summary", "");
        String customerName = body.getOrDefault("customerName", "");
        try {
            SessionInsightService.TicketResult t = insightService.createTicket(sessionId, intent, summary, customerName);
            return ResponseEntity.ok(Map.of("id", t.id(), "status", t.status()));
        } catch (Exception e) {
            log.warn("[Insight] 工单创建返回失败态: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("error", e.getMessage(), "status", "FAILED"));
        }
    }
}
