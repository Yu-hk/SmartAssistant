/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.core;

import com.example.smartassistant.consumer.client.RouterClient;
import com.example.smartassistant.consumer.service.infrastructure.DataMaskingService;
import com.example.smartassistant.consumer.service.sentiment.TurnInsight;
import com.example.smartassistant.common.tracing.DistributedTracingService;
import com.example.smartassistant.consumer.service.infrastructure.RoutingCallLogService;
import com.example.smartassistant.consumer.service.infrastructure.TokenUsageExtractor;
import com.example.smartassistant.consumer.service.infrastructure.ToolUsageExtractor;
import com.example.smartassistant.common.audit.ToolUsageCache;
import com.example.smartassistant.common.memory.EntityProfileService;
import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
import com.example.smartassistant.consumer.service.session.SessionManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.List;
import java.util.Objects;
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

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.smartassistant.consumer.service.dispatch.PriorityRoutingDispatcher priorityDispatcher;

    private final SessionManagementService sessionManagementService;
    private final UserProfileService userProfileService; // ⭐ 用户画像服务
    private final RouterClient routerClient;
    private final RoutingCallLogService routingCallLogService;
    private final DistributedTracingService tracingService; // ⭐ 分布式追踪
    private final DataMaskingService maskingService; // ⭐ 数据脱敏
    private final EntityProfileService entityProfileService;
    private final ConversationPreprocessingService preprocessingService;

    public ChatConsumerService(
            SessionManagementService sessionManagementService,
            UserProfileService userProfileService, // ⭐ 用户画像服务
            RouterClient routerClient,
            RoutingCallLogService routingCallLogService,
            DistributedTracingService tracingService,
            DataMaskingService maskingService,
            EntityProfileService entityProfileService,
            ConversationPreprocessingService preprocessingService) {
        this.sessionManagementService = sessionManagementService;
        this.userProfileService = userProfileService; // ⭐ 用户画像服务
        this.routerClient = routerClient;
        this.routingCallLogService = routingCallLogService;
        this.tracingService = tracingService;
        this.maskingService = maskingService;
        this.entityProfileService = entityProfileService;
        this.preprocessingService = preprocessingService;
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
        String traceReqId = requestId != null && !requestId.isBlank()
                ? requestId : UUID.randomUUID().toString();
        String threadId = sessionManagementService.getOrCreateThreadId(userId != null ? userId : "anonymous");
        tracingService.startTrace(traceReqId, threadId);
        tracingService.injectToLog("收到请求: userId=" + maskingService.maskUsername(userId != null ? userId : "anonymous"));
        
        log.info("[Consumer] 收到请求: userId={}, userIdLong={}, question={}", userId, userIdLong, question);

        // Step 1: 画像旁路异步准备；Product 整轮短暂等待，超时不阻断业务。
        TurnInsight insight = preprocessingService.prepare(userIdLong, threadId, traceReqId, question);

        // Step 2: 用户画像始终留在 Consumer；Router 只接收任务与 Consumer 的路由提示。
        log.info("[Consumer] 转发请求到 Router Service (纯文本question), questionLength={}", question.length());
        Map<String, Object> routeResponse = priorityDispatcher != null
                ? priorityDispatcher.route(question, userId, threadId, traceReqId, insight, 120000, null)
                : routerClient.callRouterRaw(question, userId, null, traceReqId, !insight.bypassAnswerCache());
        String response = insight.adaptReply((String) routeResponse.getOrDefault("result", ""));
        String routedAgent = (String) routeResponse.getOrDefault("agentName", null);
        String intentTag = (String) routeResponse.get("intentTag");  // ⭐ 读取意图标签
        TokenUsageExtractor.TokenUsage tokenUsage = TokenUsageExtractor.extract(routeResponse);
        ToolUsageCache.ToolUsage toolUsage = ToolUsageExtractor.extract(routeResponse);

        // Step 3.5: 实体画像提取（异步，不阻塞主流程）
        if (userIdLong != null) {
            entityProfileService.extractAndStore(userIdLong, question, response, traceReqId);
        }

        // Step 3.6: 更新意图分布（优先使用 intentTag，降级到 agentName）
        if (userIdLong != null && intentTag != null && !intentTag.isBlank()) {
            userProfileService.updateIntentDistribution(userIdLong, intentTag);
        } else if (userIdLong != null && routedAgent != null && !routedAgent.isBlank() && !"none".equals(routedAgent)) {
            userProfileService.updateIntentDistribution(userIdLong, routedAgent);  // 降级
        }

        // Step 4: 记录调用日志
        long latencyMs = System.currentTimeMillis() - startTime;
        String finalStatus = responseStatus(routeResponse);
        routingCallLogService.saveLog(
                userIdLong,
                threadId,
                question,
                effectiveAgent(routedAgent, intentTag),
                "ROUTER_SERVICE",
                latencyMs,
                finalStatus,
                response,
                tokenUsage.promptTokens(),
                tokenUsage.completionTokens(),
                tokenUsage.totalTokens(),
                question,
                toolUsage
        );
        if ("SUCCESS".equals(finalStatus) && userIdLong != null) {
            scheduleProfileCommit(userIdLong, traceReqId);
        }

        log.info("[Consumer] 总耗时: {} ms, 响应长度: {} 字符", latencyMs, response.length());
        
        // Step 5: 结束追踪 ⭐
        tracingService.endTrace();

        return response;
    }

    public Map<String, Object> calculateWithSession(String userId, String question, String sessionId, String requestIdParam) {
        long startTime = System.currentTimeMillis();
        String originalQuestion = question;

        Long userIdLong = parseUserId(userId);
        String traceReqId = requestIdParam != null && !requestIdParam.isBlank()
                ? requestIdParam : UUID.randomUUID().toString();
        String effectiveSessionId = sessionId != null && !sessionId.isBlank()
                ? sessionId : traceReqId;
        String threadId = sessionManagementService.getOrCreateThreadId(userId != null ? userId : "anonymous");
        tracingService.startTrace(traceReqId, threadId);
        tracingService.injectToLog("收到请求(含session): userId=" + maskingService.maskUsername(userId != null ? userId : "anonymous"));

        log.info("[Consumer] 收到请求(含session): userId={}, sessionId={}, question={}", userId, sessionId, question);

        TurnInsight insight = preprocessingService.prepare(userIdLong, effectiveSessionId, traceReqId, originalQuestion);

        // Step 2: 用户画像始终留在 Consumer；Router 只接收任务与 Consumer 的路由提示。
        log.info("[Consumer] 转发请求到 Router Service(含session), questionLength={}", question.length());
        Map<String, Object> response = new java.util.HashMap<>(priorityDispatcher != null
                ? priorityDispatcher.route(question, userId, effectiveSessionId, traceReqId, insight, 120000, null)
                : routerClient.callRouterRaw(question, userId, effectiveSessionId, traceReqId, !insight.bypassAnswerCache()));
        response.put("sentiment", insight);
        response.computeIfPresent("result", (key, value) -> value instanceof String text ? insight.adaptReply(text) : value);
        
        // Step 3.5: 更新意图分布
        String routedAgent = (String) response.get("agentName");
        String intentTag = (String) response.get("intentTag");
        TokenUsageExtractor.TokenUsage tokenUsage = TokenUsageExtractor.extract(response);
        ToolUsageCache.ToolUsage toolUsage = ToolUsageExtractor.extract(response);
        tokenUsage.copyTo(response);
        ToolUsageExtractor.copyTo(toolUsage, response);
        response.put("sessionId", effectiveSessionId);
        if (userIdLong != null && routedAgent != null && !routedAgent.isBlank() && !"none".equals(routedAgent)) {
            userProfileService.updateIntentDistribution(userIdLong, routedAgent);
        }

        // Step 4: 记录调用日志
        long latencyMs = System.currentTimeMillis() - startTime;
        String finalStatus = responseStatus(response);
        routingCallLogService.saveLog(
                userIdLong,
                effectiveSessionId,
                originalQuestion,
                effectiveAgent(routedAgent, intentTag),
                "ROUTER_SERVICE",
                latencyMs,
                finalStatus,
                Objects.toString(response.get("result"), ""),
                tokenUsage.promptTokens(),
                tokenUsage.completionTokens(),
                tokenUsage.totalTokens(),
                question,
                toolUsage
        );
        if ("SUCCESS".equals(finalStatus) && userIdLong != null) {
            scheduleProfileCommit(userIdLong, traceReqId);
        }

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

    private String effectiveAgent(String routedAgent, String intentTag) {
        if (routedAgent != null && !routedAgent.isBlank() && !"none".equalsIgnoreCase(routedAgent)) {
            return routedAgent;
        }
        if (intentTag != null && !intentTag.isBlank()) {
            return intentTag;
        }
        return "unknown";
    }

    private boolean hasMeaningfulError(Map<String, Object> response) {
        if (response == null) {
            return true;
        }
        Object error = response.get("error");
        if (error != null && !Objects.toString(error, "").isBlank()) {
            return true;
        }
        return Boolean.FALSE.equals(response.get("success"));
    }

    private String responseStatus(Map<String, Object> response) {
        if (hasMeaningfulError(response)) {
            return "FAILED";
        }
        if (Boolean.TRUE.equals(response.get("clarification"))) {
            return "PARTIAL_SUCCESS";
        }
        return "SUCCESS";
    }

    /**
     * 判断该问题是否具有偏好提取价值
     * <p>
     * 以下类型的问题不含有用户长期偏好信息，跳过 LLM 提取以节省资源：
     * - 通用问候语
     * - 纯知识性问答（"什么是"、"怎么"等）
     *
     * @param question 用户输入
     * @return true = 值得提取偏好；false = 跳过
     */
    private boolean isPreferenceWorthyRequest(String question) {
        return UserProfileService.isPreferenceWorthyRequest(question);
    }

    private void scheduleProfileCommit(Long userId, String requestId) {
        try {
            userProfileService.commitAfterSuccessfulTurn(userId, requestId);
        } catch (RuntimeException error) {
            log.error("[Consumer] 调度画像提交失败: requestId={}, error={}",
                    requestId, error.getMessage());
        }
    }

}
