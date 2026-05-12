/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.infrastructure.PromptMonitoringService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Prompt 监控管理 API
 */
@RestController
@RequestMapping("/api/prompt-monitoring")
public class PromptMonitoringController {
    
    private final PromptMonitoringService monitoringService;
    
    public PromptMonitoringController(PromptMonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }
    
    /**
     * 获取监控统计信息
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(monitoringService.getStats());
    }
    
    /**
     * 重置统计数据
     */
    @PostMapping("/reset")
    public ResponseEntity<String> resetStats() {
        monitoringService.resetStats();
        return ResponseEntity.ok("统计数据已重置");
    }
}
