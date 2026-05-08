package com.example.smartassistant.consumer.service.core;

import com.example.smartassistant.consumer.client.RouterClient;
import com.example.smartassistant.consumer.config.PromptGrayReleaseConfig;
import com.example.smartassistant.consumer.dto.StructuredPrompt;
import com.example.smartassistant.consumer.service.infrastructure.*;
import com.example.smartassistant.consumer.service.recommendation.UserProfileService;
import com.example.smartassistant.consumer.service.session.SessionManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 数学计算远程调用服务
 * <p>
 * 架构变更：路由决策已迁移到独立的 Router Service
 * Consumer 职责：
 * 1. 用户认证和会话管理
 * 2. 创建和更新用户画像
 * 3. 构建完整 Prompt（用户画像 + 问题）
 * 4. 转发请求给 Router Service
 * 5. 记录调用历史日志
 */
@Service
public class MathConsumerService {

    private static final Logger log = LoggerFactory.getLogger(MathConsumerService.class);

    private final SessionManagementService sessionManagementService;
    private final UserProfileService userProfileService; // ⭐ 用户画像服务
    private final RouterClient routerClient;
    private final RoutingCallLogService routingCallLogService;
    private final PromptMonitoringService promptMonitoringService;
    private final DistributedTracingService tracingService; // ⭐ 分布式追踪
    private final DataMaskingService maskingService; // ⭐ 数据脱敏
    private final PromptGrayReleaseConfig grayReleaseConfig; // ⭐ 灰度发布

    public MathConsumerService(
            SessionManagementService sessionManagementService,
            UserProfileService userProfileService, // ⭐ 用户画像服务
            RouterClient routerClient,
            RoutingCallLogService routingCallLogService,
            PromptMonitoringService promptMonitoringService,
            DistributedTracingService tracingService,
            DataMaskingService maskingService,
            PromptGrayReleaseConfig grayReleaseConfig) {
        this.sessionManagementService = sessionManagementService;
        this.userProfileService = userProfileService; // ⭐ 用户画像服务
        this.routerClient = routerClient;
        this.routingCallLogService = routingCallLogService;
        this.promptMonitoringService = promptMonitoringService;
        this.tracingService = tracingService;
        this.maskingService = maskingService;
        this.grayReleaseConfig = grayReleaseConfig;
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

        // Step 2: 构建完整 Prompt（根据灰度配置决定格式）⭐
        String fullPrompt;
        if (grayReleaseConfig.shouldUseJsonFormat(userIdLong)) {
            log.info("[Consumer] 使用 JSON 格式 Prompt (灰度)");
            fullPrompt = buildFullPrompt(userIdLong, question);
        } else {
            log.info("[Consumer] 使用文本格式 Prompt (旧版)");
            fullPrompt = buildTextPrompt(userIdLong, question);  // 降级为文本格式
        }
        
        // Step 3: 转发给 Router Service
        log.info("[Consumer] 转发请求到 Router Service, promptLength={}", fullPrompt.length());
        Map<String, Object> routeResponse = routerClient.callRouterRaw(fullPrompt, userId, null, requestId);
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
    
    /**
     * ⭐ 带会话 ID 的完整调用，返回 Router 的完整响应（包含 suggestions）
     *
     * @param userId 用户 ID
     * @param question 用户问题
     * @param sessionId 会话 ID
     * @return Router 返回的完整响应 Map（包含 result、suggestions 等）
     */
    public Map<String, Object> calculateWithSession(String userId, String question, String sessionId) {
        return calculateWithSession(userId, question, sessionId, null);
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

        // Step 2: 构建完整 Prompt
        String fullPrompt;
        if (grayReleaseConfig.shouldUseJsonFormat(userIdLong)) {
            fullPrompt = buildFullPrompt(userIdLong, question);
        } else {
            fullPrompt = buildTextPrompt(userIdLong, question);
        }

        // Step 3: 转发给 Router Service（传递 sessionId）
        log.info("[Consumer] 转发请求到 Router Service(含session), promptLength={}", fullPrompt.length());
        Map<String, Object> response = routerClient.callRouterRaw(fullPrompt, userId, sessionId, requestIdParam);
        
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
     * 构建文本格式 Prompt（旧版，用于灰度降级）
     */
    private String buildTextPrompt(Long userId, String question) {
        StringBuilder prompt = new StringBuilder();
        
        // 添加用户画像
        if (userId != null) {
            String userProfilePrompt = userProfileService.buildUserProfilePrompt(userId);
            if (!userProfilePrompt.isEmpty()) {
                prompt.append(userProfilePrompt);
                prompt.append("\n");
            }
        }
        
        // 添加当前问题
        prompt.append("【当前问题】\n");
        prompt.append(question);
        
        return prompt.toString();
    }
    
    /**
     * 构建完整 Prompt（JSON 格式 + 增强功能）
     * 说明：历史上下文已由 Router 的语义缓存和文件记忆处理，
     *       此处不再从 DB 读取。 
     * 1. 标准化结构，易于解析
     * 2. 类型安全，支持嵌套
     * 3. 版本控制，便于升级
     * 4. 元数据追踪，便于监控
     * 5. 压缩优化，减少传输量
     */
    private String buildFullPrompt(Long userId, String question) {
        long buildStart = System.currentTimeMillis();
        
        // Step 1: 构建用户画像
        StructuredPrompt.UserProfile userProfile = null;
        if (userId != null) {
            String userProfileText = userProfileService.buildUserProfilePrompt(userId);
            if (!userProfileText.isEmpty()) {
                userProfile = parseUserProfile(userProfileText);
            }
        }
        
        // Step 2: 历史对话已由 Router 和文件记忆处理，此处不再从 DB 读取
        String userIdStr = userId != null ? userId.toString() : "anonymous";
        String threadId = sessionManagementService.getOrCreateThreadId(userIdStr);
        
        // Step 3: 构建元数据（从 MDC 中获取 requestId，保证一致性）⭐
        String mdcRequestId = tracingService.getCurrentContext().get("requestId");
        StructuredPrompt.RequestMetadata metadata = StructuredPrompt.RequestMetadata.builder()
                .requestId(mdcRequestId != null ? mdcRequestId : UUID.randomUUID().toString())  // ⭐ 优先使用 MDC 中的 requestId
                .timestamp(System.currentTimeMillis())
                .clientId("web-app")  // 可从 HttpServletRequest 中获取 User-Agent 判断
                .userId(userId)
                .sessionId(threadId)
                .build();
        
        // Step 5: 构建结构化 Prompt
        StructuredPrompt structuredPrompt = StructuredPrompt.builder()
                .version("1.1")
                .metadata(metadata)
                .userProfile(userProfile)
                .currentQuestion(question)
                .compressed(false)
                .build();
        
        // Step 6: 转换为 JSON 字符串
        String jsonPrompt = structuredPrompt.toJson();
        
        // Step 7: 记录监控数据 ⭐
        long buildEnd = System.currentTimeMillis();
        promptMonitoringService.recordRequest(jsonPrompt.length());
        
        // Step 8: 脱敏日志输出 ⭐
        String maskedPrompt = maskingService.maskStructuredPrompt(jsonPrompt);
        log.debug("[Consumer] 构建 JSON Prompt (脱敏): {}", maskedPrompt.substring(0, Math.min(200, maskedPrompt.length())));
        
        log.debug("[Consumer] 构建 JSON Prompt: userId={}, threadId={}, buildTime={}ms", 
                userId, threadId, buildEnd - buildStart);
        log.debug("[Consumer] JSON Prompt 长度: {} 字符", jsonPrompt.length());
        
        return jsonPrompt;
    }
    
    /**
     * 解析用户画像文本为结构化对象
     */
    private StructuredPrompt.UserProfile parseUserProfile(String userProfileText) {
        // 简单解析：按行分割提取关键信息
        String[] lines = userProfileText.split("\n");
        StringBuilder preferences = new StringBuilder();
        StringBuilder historyBehavior = new StringBuilder();
        
        for (String line : lines) {
            if (line.contains("偏好") || line.contains("喜欢")) {
                preferences.append(line.trim()).append("; ");
            } else if (line.contains("历史") || line.contains("行为")) {
                historyBehavior.append(line.trim()).append("; ");
            }
        }
        
        return StructuredPrompt.UserProfile.builder()
                .preferences(preferences.toString().trim())
                .historyBehavior(historyBehavior.toString().trim())
                .build();
    }
    
    /**
     * 解析历史对话文本为消息列表
     */
    private List<StructuredPrompt.ConversationMessage> parseConversationHistory(String historyText) {
        List<StructuredPrompt.ConversationMessage> messages = new ArrayList<>();
        String[] lines = historyText.split("\n");
        
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) continue;
            
            if (trimmedLine.startsWith("用户:")) {
                String content = trimmedLine.substring(3).trim();
                messages.add(StructuredPrompt.ConversationMessage.builder()
                        .role("user")
                        .content(content)
                        .timestamp(System.currentTimeMillis())
                        .build());
            } else if (trimmedLine.startsWith("Agent:")) {
                String content = trimmedLine.substring(6).trim();
                messages.add(StructuredPrompt.ConversationMessage.builder()
                        .role("agent")
                        .content(content)
                        .timestamp(System.currentTimeMillis())
                        .build());
            }
        }
        
        return messages;
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

    /**
     * 从 SecurityContext 中提取认证用户 ID
     * ⚠️ 已废弃：Consumer 不再使用 Spring Security，userId 由 Gateway 通过 X-User-Id Header 传递
     */
    private Long getCurrentUserId() {
        // 已移除 Spring Security，返回 null
        // userId 将由 Controller 层从 X-User-Id Header 获取并传递给 Router Service
        return null;
    }

}
