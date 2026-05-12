/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.core;

import com.example.smartassistant.consumer.client.RouterClient;
import com.example.smartassistant.consumer.service.infrastructure.DataMaskingService;
import com.example.smartassistant.common.tracing.DistributedTracingService;
import com.example.smartassistant.consumer.service.infrastructure.RoutingCallLogService;
import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
import com.example.smartassistant.consumer.service.session.SessionManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * 对话消费者核心服务
 * <p>
 * 架构变更：路由决策已迁移到独立的 Router Service
 * Consumer 职责：
 * 1. 用户认证和会话管理
 * 2. 创建和更新用户画像
 * 3. 构建完整 Prompt（用户画像 + 问题）
 * 4. 转发请求给 Router Service
 * 5. 记录调用历史日志
 * <p>
 * ⭐ 2026-05-11: 从 MathConsumerService 重命名为 ChatConsumerService，
 *    消除与"数学计算"无关的历史命名歧义
 */
@Service
public class ChatConsumerService {

    private static final Logger log = LoggerFactory.getLogger(ChatConsumerService.class);

    private final SessionManagementService sessionManagementService;
    private final UserProfileService userProfileService; // ⭐ 用户画像服务
    private final RouterClient routerClient;
    private final RoutingCallLogService routingCallLogService;
    private final DistributedTracingService tracingService; // ⭐ 分布式追踪
    private final DataMaskingService maskingService; // ⭐ 数据脱敏

    public ChatConsumerService(
            SessionManagementService sessionManagementService,
            UserProfileService userProfileService, // ⭐ 用户画像服务
            RouterClient routerClient,
            RoutingCallLogService routingCallLogService,
            DistributedTracingService tracingService,
            DataMaskingService maskingService) {
        this.sessionManagementService = sessionManagementService;
        this.userProfileService = userProfileService; // ⭐ 用户画像服务
        this.routerClient = routerClient;
        this.routingCallLogService = routingCallLogService;
        this.tracingService = tracingService;
        this.maskingService = maskingService;
    }

    /**
     * 调用远程 Agent（通过 Router Service）
     * <p>
     * 新架构流程：
     * 1. 获取用户 ID
     * 2. 更新用户画像（提取偏好并保存）
     * 3. 构建完整 Prompt（用户画像 + 历史对话 + 原始问题）
     * 4. 转发给 Router Service（Router 会提取关键词）
     * 5. 记录调用日志
     *
     * @param userId 用户 ID（来自 X-User-Id Header，可能为 null）
     * @param question 用户问题
     * @return Agent 处理结果
     */
    public String calculate(String userId, String question) {
        return calculate(userId, question, null);
    }

    public String calculate(String userId, String question, String requestId) {
        long startTime = System.currentTimeMillis();
        
        // Step 0: 解析用户 ID（从 String 转换为 Long）
        Long userIdLong = parseUserId(userId);
        
        // Step 0.5: 启动分布式追踪 ⭐
        String traceReqId = UUID.randomUUID().toString();
        String threadId = sessionManagementService.getOrCreateThreadId(userId != null ? userId : "anonymous");
        tracingService.startTrace(traceReqId, threadId);
        tracingService.injectToLog("收到请求: userId=" + maskingService.maskUsername(userId != null ? userId : "anonymous"));
        
        log.info("[Consumer] 收到请求: userId={}, userIdLong={}, question={}", userId, userIdLong, question);

        // Step 1: 更新用户画像（仅对有偏好价值的请求）⭐
        if (userIdLong != null && isPreferenceWorthyRequest(question)) {
            userProfileService.extractAndUpdatePreferences(userIdLong, question, null);
            log.debug("[Consumer] 用户画像已更新: userId={}", userIdLong);
        } else if (userIdLong != null) {
            log.debug("[Consumer] 跳过画像提取（无偏好价值）: userId={}, question={}", userIdLong, question);
        }

        // Step 2: 构建用户画像文本（独立字段，不塞入 question）
        String userProfileText = null;
        if (userIdLong != null) {
            userProfileText = userProfileService.buildUserProfilePrompt(userIdLong);
        }
        
        // Step 3: 转发纯文本 question + 独立 userProfile 给 Router Service
        log.info("[Consumer] 转发请求到 Router Service (纯文本question), questionLength={}", question.length());
        Map<String, Object> routeResponse = routerClient.callRouterRaw(question, userId, null, requestId, userProfileText, null);
        String response = (String) routeResponse.getOrDefault("result", "");
        String routedAgent = (String) routeResponse.getOrDefault("agentName", null);
        String intentTag = (String) routeResponse.get("intentTag");  // ⭐ 读取意图标签

        // Step 3.5: 更新意图分布（优先使用 intentTag，降级到 agentName）
        if (userIdLong != null && intentTag != null && !intentTag.isBlank()) {
            userProfileService.updateIntentDistribution(userIdLong, intentTag);
        } else if (userIdLong != null && routedAgent != null && !routedAgent.isBlank() && !"none".equals(routedAgent)) {
            userProfileService.updateIntentDistribution(userIdLong, routedAgent);  // 降级
        }

        // Step 4: 记录调用日志
        long latencyMs = System.currentTimeMillis() - startTime;
        routingCallLogService.saveLog(
                userId,
                question,
                "router_service",
                "ROUTER_SERVICE",
                latencyMs,
                "SUCCESS"
        );

        log.info("[Consumer] 总耗时: {} ms, 响应长度: {} 字符", latencyMs, response.length());
        
        // Step 5: 结束追踪 ⭐
        tracingService.endTrace();

        return response;
    }

    public Map<String, Object> calculateWithSession(String userId, String question, String sessionId, String requestIdParam) {
        long startTime = System.currentTimeMillis();

        Long userIdLong = parseUserId(userId);
        String traceReqId = UUID.randomUUID().toString();
        String threadId = sessionManagementService.getOrCreateThreadId(userId != null ? userId : "anonymous");
        tracingService.startTrace(traceReqId, threadId);
        tracingService.injectToLog("收到请求(含session): userId=" + maskingService.maskUsername(userId != null ? userId : "anonymous"));

        log.info("[Consumer] 收到请求(含session): userId={}, sessionId={}, question={}", userId, sessionId, question);

        // Step 1: 更新用户画像
        if (userIdLong != null && isPreferenceWorthyRequest(question)) {
            userProfileService.extractAndUpdatePreferences(userIdLong, question, null);
        }

        // Step 2: 构建用户画像文本（独立字段，不塞入 question）
        String userProfileText = null;
        if (userIdLong != null) {
            userProfileText = userProfileService.buildUserProfilePrompt(userIdLong);
        }

        // Step 3: 转发纯文本 question + 独立 userProfile 给 Router Service（传递 sessionId）
        log.info("[Consumer] 转发请求到 Router Service(含session), questionLength={}", question.length());
        Map<String, Object> response = routerClient.callRouterRaw(question, userId, sessionId, requestIdParam, userProfileText, null);
        
        // Step 3.5: 更新意图分布
        String routedAgent = (String) response.get("agentName");
        if (userIdLong != null && routedAgent != null && !routedAgent.isBlank() && !"none".equals(routedAgent)) {
            userProfileService.updateIntentDistribution(userIdLong, routedAgent);
        }

        // Step 4: 记录调用日志
        long latencyMs = System.currentTimeMillis() - startTime;
        routingCallLogService.saveLog(
                userId,
                question,
                "router_service",
                "ROUTER_SERVICE",
                latencyMs,
                response.containsKey("error") ? "PARTIAL_SUCCESS" : "SUCCESS"
        );

        log.info("[Consumer] 总耗时: {} ms, 响应包含 suggestions={}", latencyMs, response.containsKey("suggestions"));

        tracingService.endTrace();
        return response;
    }

    /**
     * 兼容旧版本（不带 userId）
     */
    public String calculate(String question) {
        return calculate(null, question);
    }

    /**
     * 解析用户 ID
     * @param userId String 类型的 userId（可能为 null）
     * @return Long 类型的 userId（如果解析失败返回 null）
     */
    private Long parseUserId(String userId) {
        if (userId == null || userId.isBlank() || "anonymous".equalsIgnoreCase(userId)) {
            return null;
        }
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException e) {
            // 如果是 username（如 "admin"），返回 null
            log.debug("[Consumer] userId '{}' 不是数字，尝试使用 username", userId);
            return null;
        }
    }

    /**
     * 判断该问题是否具有偏好提取价值
     * <p>
     * 以下类型的问题不含有用户长期偏好信息，跳过 LLM 提取以节省资源：
     * - 天气/气象查询（临时地点，不代表偏好）
     * - 通用问候语
     * - 纯知识性问答（"什么是"、"怎么"等）
     *
     * @param question 用户输入
     * @return true = 值得提取偏好；false = 跳过
     */
    private boolean isPreferenceWorthyRequest(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String lower = question.toLowerCase().trim();

        // 天气/气象类 - 临时查询，地点不代表偏好
        String[] weatherKeywords = { "天气", "气温", "几度", "下雨", "下雪", "雨雪", "预报", "晴", "阴天", "雾霾", "pm2.5", "空气质量" };
        for (String kw : weatherKeywords) {
            if (lower.contains(kw)) return false;
        }

        // 通用问候/闲聊 - 无任何偏好信息
        String[] greetingKeywords = { "你好", "hello", "hi ", "嗨", "谢谢", "感谢", "再见", "拜拜", "早上好", "晚上好", "下午好" };
        for (String kw : greetingKeywords) {
            if (lower.contains(kw)) return false;
        }

        // 纯知识问答 - 不含个人偏好
        String[] knowledgeKeywords = { "什么是", "怎么", "如何", "为什么", "是什么", "解释", "帮我查", "帮我搜" };
        for (String kw : knowledgeKeywords) {
            if (lower.contains(kw)) return false;
        }

        return true;
    }

}
