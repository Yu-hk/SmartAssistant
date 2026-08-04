/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.infrastructure;

import com.example.smartassistant.consumer.entity.RoutingCallLog;
import com.example.smartassistant.consumer.mapper.RoutingCallLogMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 路由调用日志服务 (MyBatis Plus)
 * 负责记录 Consumer 到 Router 的调用历史
 */
@Service
public class RoutingCallLogService {
    
    private static final Logger log = LoggerFactory.getLogger(RoutingCallLogService.class);
    
    private final RoutingCallLogMapper callLogMapper;
    private final JdbcTemplate jdbcTemplate;
    
    public RoutingCallLogService(RoutingCallLogMapper callLogMapper, JdbcTemplate jdbcTemplate) {
        this.callLogMapper = callLogMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void ensureIdentitySchema() {
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE routing_call_log ADD COLUMN IF NOT EXISTS user_id BIGINT");
            jdbcTemplate.update(
                    "UPDATE routing_call_log SET user_id = CAST(session_id AS BIGINT) " +
                    "WHERE user_id IS NULL AND session_id ~ '^[1-9][0-9]*$' " +
                    "AND LENGTH(session_id) <= 18");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_routing_call_log_user_created " +
                    "ON routing_call_log (user_id, created_at DESC)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_routing_call_log_user_session " +
                    "ON routing_call_log (user_id, session_id)");
        } catch (Exception exception) {
            log.warn("[RoutingCallLog] 用户归属字段初始化失败: {}", exception.getMessage());
        }
    }
    
    /**
     * 异步保存路由调用日志
     */
    @Async("asyncRouteExecutor")
    public void saveLog(Long userId, String sessionId, String userInput, String routedAgent,
                        String routeMethod, Long latencyMs, String status, String responseSummary) {
        try {
            RoutingCallLog callLog = new RoutingCallLog();
            callLog.setUserId(userId);
            callLog.setSessionId(sessionId);
            callLog.setUserInput(userInput);
            callLog.setRoutedAgent(routedAgent);
            callLog.setRouteMethod(routeMethod);
            callLog.setLatencyMs(latencyMs);
            callLog.setStatus(status);
            callLog.setResponseSummary(abbreviate(responseSummary, 500));
            
            callLogMapper.insert(callLog);
            log.debug("[RoutingCallLog] 日志保存成功: sessionId={}, agent={}", sessionId, routedAgent);
        } catch (Exception e) {
            // ⭐ 优雅降级: 如果表不存在或数据库错误,只记录警告,不影响主流程
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("关系 \"routing_call_log\" 不存在")) {
                log.warn("[RoutingCallLog] ⚠️ routing_call_log 表不存在,跳过日志记录 (可执行 SQL 创建表或忽略此警告)");
            } else {
                log.error("[RoutingCallLog] 日志保存失败: {}", e.getMessage());
            }
        }
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
}
