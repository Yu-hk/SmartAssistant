/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.common.feedback.UserFeedbackService;
import com.example.smartassistant.consumer.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户反馈控制器 — 前端可在此提交对 Agent 回复的评价。
 *
 * <p>前端在 SSE 流结束后，显示点赞/踩按钮，用户点击后 POST 到此接口。</p>
 *
 * <p>请求体示例：</p>
 * <pre>
 * {
 *   "sessionId": "session_abc",
 *   "question": "我的订单到哪了",
 *   "response": "您的订单已发货...",
 *   "rating": "like",
 *   "reason": "回复很准确"
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    private final UserFeedbackService feedbackService;

    public FeedbackController(UserFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping
    public String submitFeedback(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader,
            @RequestBody Map<String, String> body) {
        String userId = String.valueOf(
                AuthenticatedUser.require(userIdHeader, null, roleHeader).userId());
        String sessionId = body.get("sessionId");
        String question = body.get("question");
        String response = body.get("response");
        String rating = body.get("rating");
        String reason = body.get("reason");

        if (rating == null || (!"like".equals(rating) && !"dislike".equals(rating))) {
            return "❌ rating 必须为 'like' 或 'dislike'";
        }

        feedbackService.recordFeedback(userId, sessionId, question, response, rating, reason);
        log.info("[Feedback] 提交反馈: userId={}, rating={}", userId, rating);
        return "✅ 感谢反馈";
    }
}
