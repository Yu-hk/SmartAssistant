/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.sentiment.SentimentAnalysisService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实时会话洞察接口。
 */
@RestController
@RequestMapping("/api/insight")
public class InsightController {

    private final SentimentAnalysisService sentimentAnalysisService;

    public InsightController(SentimentAnalysisService sentimentAnalysisService) {
        this.sentimentAnalysisService = sentimentAnalysisService;
    }

    @PostMapping("/emotion")
    public EmotionResponse analyzeEmotion(@RequestBody EmotionRequest request) {
        String text = request != null ? request.text() : null;
        SentimentAnalysisService.SentimentResult result = sentimentAnalysisService.analyze(text);

        // 前端进度条展示从正面到愤怒的风险强度：0、25、50、75、100。
        int score = (result.level() - 1) * 25;
        return new EmotionResponse(result.name(), score, result.confidence());
    }

    public record EmotionRequest(String text, String triggerTopic) {
    }

    public record EmotionResponse(String label, int score, int confidence) {
    }
}
