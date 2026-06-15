/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.common.api.AgentApiResponse;
import com.example.smartassistant.common.api.AgentApiResponses;
import com.example.smartassistant.common.api.AgentError;
import com.example.smartassistant.common.api.AgentSyncResponse;
import com.example.smartassistant.common.monitoring.AgentErrorMetricsCollector;
import com.example.smartassistant.common.tool.ToolLogContext;
import com.example.smartassistant.consumer.service.cache.AnswerCacheService;
import com.example.smartassistant.consumer.service.cache.AnswerPersonalizationService;
import com.example.smartassistant.consumer.service.core.ChatConsumerService;
import com.example.smartassistant.consumer.service.session.ConversationDocumentService;
import com.example.smartassistant.consumer.service.session.ConversationValueService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 智能对话 REST API - 纯代理模式
 * Consumer 只负责传递用户提问到 Router Service,不做任何处理
 */
@RestController
@RequestMapping("/api/math")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final ChatConsumerService chatService;
    private final AnswerCacheService answerCacheService;  // ⭐ Phase 1: 答案缓存服务
    private final AnswerPersonalizationService personalizationCacheService;  // ⭐ 方案E: 缓存预热
    private final ConversationValueService conversationValueService;
    private final ConversationDocumentService conversationDocumentService;

    // ⭐ 会话轮数追踪：sessionId -> turnCount（使用 Caffeine 缓存，1 小时自动过期，最大 10000 条目）
    private final Cache<String, Integer> turnCounts = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(10000)
            .recordStats()
            .build();

    private final AgentErrorMetricsCollector metricsCollector;

    public ChatController(ChatConsumerService chatService,
                         AnswerCacheService answerCacheService,
                         AnswerPersonalizationService personalizationCacheService,
                          ConversationValueService conversationValueService,
                          ConversationDocumentService conversationDocumentService,
                          AgentErrorMetricsCollector metricsCollector) {
        this.chatService = chatService;
        this.answerCacheService = answerCacheService;
        this.personalizationCacheService = personalizationCacheService;
        this.conversationValueService = conversationValueService;
        this.conversationDocumentService = conversationDocumentService;
        this.metricsCollector = metricsCollector;
    }

    /**
     * 智能对话接口 - 纯代理 + 数据查询增强
     * POST /api/math/chat
     * Body: {"message": "成都有什么美食？", "userId": "user123", "sessionId": "session_abc"}
     * 
     * <p>增强功能：</p>
     * <ul>
     *     <li>@RateLimiter: 限制每秒 30 个请求，防止滥用</li>
     *     <li>⭐ 自动识别数据查询请求，路由到 MCP/MyBatis</li>
     *     <li>⭐ 支持自然语言数据分析</li>
     *     <li>⭐ Phase 1: 答案缓存（完全响应式）</li>
     * </ul>
     */
    @PostMapping("/chat")
    @RateLimiter(name = "chatRateLimiter")
    public Mono<AgentApiResponse<AgentSyncResponse>> chat(
            @RequestHeader(value = "X-User-Id", required = false) String userIdFromHeader,
            @RequestHeader(value = "X-User-Role", required = false) String userRoleFromHeader,
            @RequestBody Map<String, String> request) {
        long startTime = System.currentTimeMillis();
        
        // 1. 提取参数
        String message = request.getOrDefault("question", request.get("message"));
        
        // ⭐ 安全修复：完全信任 Gateway 传来的 X-User-Id Header
        // ⚠️ 不再使用请求体中的 userId，防止身份伪造攻击
        // Gateway 在 JWT 验证通过后会将用户名放入 X-User-Id Header（类型为 String）
        String userId;
        if (userIdFromHeader != null && !userIdFromHeader.isBlank()) {
            userId = userIdFromHeader;
            log.debug("[Auth] ✅ 从 Gateway Header 获取 userId: {}", userId);
        } else {
            // 未认证用户使用默认值（白名单路径不会有此情况）
            userId = "anonymous";
            log.debug("[Auth] ⚠️ 无认证信息，使用匿名用户");
        }
        
        // ⭐ 权限校验：数据统计/查询接口仅限管理员
        String role = (userRoleFromHeader != null) ? userRoleFromHeader : "ROLE_USER";
        
        String sessionId = request.getOrDefault("sessionId", null);
        
        log.info("收到对话请求: userId={}, role={}, message={}", userId, role, message);
        
        // ⭐ 提取 requestId 供后续流程使用
        String requestId = request.getOrDefault("requestId", null);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }
        final String finalRequestId = requestId;

        // ⭐ 将 requestId 设入 MDC/ThreadLocal，供 @Tool 日志切面使用
        ToolLogContext.setRequestId(finalRequestId);

        // ⭐ 方案E: 异步预热用户画像缓存（仅对已认证用户）
        if (!"anonymous".equals(userId) && !userIdFromHeader.isBlank()) {
            personalizationCacheService.preloadProfile(userId)
                .subscribe(
                    v -> log.debug("[CachePreload] ✅ 预热完成: userId={}", userId),
                    e -> log.warn("[CachePreload] ⚠️ 预热失败: {}", e.getMessage())
                );
        }
        
        // 2. 已无 DB 存储，直接处理
        
        // ⭐ 3. 统一转发给 ChatConsumerService（不再拦截数据查询请求）
        // 数据查询请求已由 DataQueryController 处理
        Mono<AgentSyncResponse> resultMono;
        if (sessionId != null) {
            // ⭐ 有 sessionId：使用带 session 的完整响应
            log.info("[交互式多意图] 使用 session 模式: sessionId={}", sessionId);
            resultMono = Mono.fromCallable(() -> chatService.calculateWithSession(userId, message, sessionId, finalRequestId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(routerMap -> {
                        String resultText = (String) routerMap.get("result");
                        String agentName = (String) routerMap.get("agentName");
                        Boolean fromCache = (Boolean) routerMap.getOrDefault("fromCache", false);
                        String intentTag = (String) routerMap.get("intentTag");
                        Boolean toolInvoked = (Boolean) routerMap.getOrDefault("toolInvoked", false);
                        return AgentSyncResponse.builder()
                                .reply(resultText != null ? resultText : "")
                                .intentTag(intentTag)
                                .toolInvoked(toolInvoked != null && toolInvoked)
                                .build();
                    });
        } else {
            // 4. 直接调用 LLM（无 session）
            log.info("[无Session] 直接调用 calculate");
            resultMono = Mono.fromCallable(() -> chatService.calculateWithSession(userId, message, null, finalRequestId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(routerMap -> {
                        String resultText = (String) routerMap.get("result");
                        String intentTag = (String) routerMap.get("intentTag");
                        Boolean toolInvoked = (Boolean) routerMap.getOrDefault("toolInvoked", false);
                        return AgentSyncResponse.builder()
                                .reply(resultText != null ? resultText : "")
                                .intentTag(intentTag)
                                .toolInvoked(toolInvoked != null && toolInvoked)
                                .build();
                    });
        }

        // 5. 组合所有异步操作
        return resultMono
            .flatMap(intermediate -> {
                String reply = intermediate.getReply();

                // ⭐ 6. 解析 Agent 回复中的建议（以 ⭐ 开头的行为建议）
                List<String> suggestions = parseStarSuggestions(reply);
                
                // 从回复中移除建议行，避免重复展示
                String cleanReply = removeStarSuggestions(reply);

                String intentTag = intermediate.getIntentTag();
                boolean toolInvoked = intermediate.isToolInvoked();

                // ⭐ 7. 对话价值评估：判断是否沉淀为用户个人文档（异步，不阻塞）
                if (sessionId != null) {
                    int turnCount = turnCounts.asMap().merge(sessionId, 1, Integer::sum);

                    ConversationValueService.ConversationValueContext ctx =
                            new ConversationValueService.ConversationValueContext(
                                    Long.valueOf(userId),
                                    sessionId,
                                    message + "\n" + cleanReply,
                                    null,  // agentName 不需要透传
                                    intentTag,
                                    turnCount,
                                    false,
                                    toolInvoked
                            );

                    try {
                        if (conversationValueService.isValuable(ctx)) {
                            conversationDocumentService.saveValuableConversation(ctx);
                        }
                    } catch (Exception e) {
                        log.warn("[ChatController] ⚠️ 对话价值评估失败: {}", e.getMessage());
                    }
                }

                long endTime = System.currentTimeMillis();
                log.info("对话完成: userId={}, suggestions={}, duration={}ms",
                        userId,
                        suggestions.size(),
                        endTime - startTime);

                // 8. 构建统一响应
                AgentSyncResponse data = AgentSyncResponse.builder()
                        .reply(cleanReply)
                        .suggestions(suggestions)
                        .intentTag(intentTag)
                        .toolInvoked(toolInvoked)
                        .build();

                return Mono.just(AgentApiResponses.ok(data, null, finalRequestId, endTime - startTime));
            });
    }

    /*
      ⭐ Phase 1: LLM 智能建议生成
      利用 ChatClient 根据对话上下文和用户画像生成语义化建议
     */
    /**
     * 从 Agent 回复中解析 ⭐ 开头的建议行
     */
    private List<String> parseStarSuggestions(String reply) {
        if (reply == null || reply.isBlank()) return List.of();

        List<String> suggestions = new ArrayList<>();
        String[] lines = reply.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("⭐")) {
                String suggestion = trimmed.substring(1).trim();
                if (!suggestion.isEmpty()) {
                    suggestions.add(suggestion);
                }
            }
        }

        return suggestions.size() >= 3 ? suggestions : List.of();
    }

    /**
     * 移除回复中的 ⭐ 建议行，避免重复展示
     */
    private String removeStarSuggestions(String reply) {
        if (reply == null || reply.isBlank()) return reply;

        StringBuilder sb = new StringBuilder();
        String[] lines = reply.split("\n");
        for (String line : lines) {
            if (!line.trim().startsWith("⭐")) {
                sb.append(line).append("\n");
            }
        }

        return sb.toString().trim();
    }

    /**
     * ⭐ 缓存统计接口 - 查看命中率等指标（仅管理员）
     * POST /api/math/cache/stats
     */
    @PostMapping("/cache/stats")
    public Mono<AgentApiResponse<Map<String, Object>>> getCacheStats(
            @RequestHeader(value = "X-User-Role", required = false) String userRoleFromHeader) {
        String role = (userRoleFromHeader != null) ? userRoleFromHeader : "ROLE_USER";
        if (!isAdmin(role)) {
            log.warn("[Auth] ⛔ 拒绝缓存统计请求: role={}", role);
            AgentError error = AgentError.builder()
                    .code(AgentApiResponses.ERROR_FORBIDDEN)
                    .title("权限不足")
                    .detail("缓存统计功能仅限管理员使用")
                    .build();
            metricsCollector.recordError(error);
            return Mono.just(AgentApiResponses.error(error, null, 0));
        }
        return answerCacheService.getCacheStats()
            .map(stats -> {
                log.info("[AnswerCache] 📊 缓存统计: {}", stats);
                return AgentApiResponses.ok(stats, null, null, 0);
            });
    }

    /**
     * ⭐ 权限校验：是否为管理员
     */
    private boolean isAdmin(String role) {
        return "ROLE_ADMIN".equalsIgnoreCase(role);
    }

}
