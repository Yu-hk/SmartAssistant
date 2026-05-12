/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.scheduler;

import com.example.smartassistant.consumer.service.session.SessionManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 会话清理定时任务
 * 定期清理过期的用户会话，防止内存泄漏
 */
@Component
public class SessionCleanupScheduler {
    
    private static final Logger log = LoggerFactory.getLogger(SessionCleanupScheduler.class);
    
    private final SessionManagementService sessionManagementService;
    
    // ⭐ 配置化：会话清理间隔（毫秒）
    @Value("${scheduler.session-cleanup-interval:60000}")
    private long cleanupInterval;
    
    public SessionCleanupScheduler(SessionManagementService sessionManagementService) {
        this.sessionManagementService = sessionManagementService;
    }
    
    /**
     * 定期执行会话清理（可配置间隔）
     */
    @Scheduled(fixedRateString = "${scheduler.session-cleanup-interval:60000}")
    public void cleanupExpiredSessions() {
        try {
            int activeCount = sessionManagementService.getActiveSessionCount();
            sessionManagementService.cleanupExpiredSessions();
            
            if (activeCount > 100) {
                log.info("[SessionCleanup] 当前活跃会话数: {}", activeCount);
            }
        } catch (Exception e) {
            log.error("[SessionCleanup] 会话清理失败", e);
        }
    }
}
