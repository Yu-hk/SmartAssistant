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

import java.math.BigDecimal;

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
        saveLog(null, sessionId, userInput, routedAgent, routeMethod,
                null, null, latencyMs, status, null);
    }

    /**
     * 保存一条完整路由审计记录。异常只影响审计，不影响对话主流程。
     */
    @Async("asyncRouteExecutor")
    public void saveLog(String requestId, String sessionId, String userInput,
                        String routedAgent, String routeMethod, Double matchScore,
                        String response, Long latencyMs, String status,
                        String errorMessage) {
        try {
            RoutingCallLog callLog = new RoutingCallLog();
            callLog.setRequestId(requestId);
            callLog.setSessionId(sessionId);
            callLog.setUserInput(userInput);
            callLog.setRoutedAgent(routedAgent);
            callLog.setRouteMethod(routeMethod);
            if (matchScore != null && Double.isFinite(matchScore)) {
                callLog.setMatchScore(BigDecimal.valueOf(matchScore));
            }
            callLog.setLlmReceivedQuestion(userInput);
            callLog.setResponseSummary(truncate(response, 500));
            callLog.setLatencyMs(latencyMs);
            callLog.setStatus(status);
            callLog.setErrorMessage(truncate(errorMessage, 2000));
            
            callLogMapper.insert(callLog);
            log.info("[RoutingCallLog] 审计已保存: requestId={}, sessionId={}, agent={}, method={}, status={}",
                    requestId, sessionId, routedAgent, routeMethod, status);
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

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
