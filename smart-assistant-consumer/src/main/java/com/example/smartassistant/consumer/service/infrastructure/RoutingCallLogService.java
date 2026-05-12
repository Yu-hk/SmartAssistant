/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.infrastructure;

import com.example.smartassistant.consumer.entity.RoutingCallLog;
import com.example.smartassistant.consumer.mapper.RoutingCallLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    
    public RoutingCallLogService(RoutingCallLogMapper callLogMapper) {
        this.callLogMapper = callLogMapper;
    }
    
    /**
     * 异步保存路由调用日志
     */
    @Async("asyncRouteExecutor")
    public void saveLog(String sessionId, String userInput, String routedAgent, 
                       String routeMethod, Long latencyMs, String status) {
        try {
            RoutingCallLog callLog = new RoutingCallLog();
            callLog.setSessionId(sessionId);
            callLog.setUserInput(userInput);
            callLog.setRoutedAgent(routedAgent);
            callLog.setRouteMethod(routeMethod);
            callLog.setLatencyMs(latencyMs);
            callLog.setStatus(status);
            
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
}
