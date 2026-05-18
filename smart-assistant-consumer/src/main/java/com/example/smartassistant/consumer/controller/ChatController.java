/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.controller;

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

    public ChatController(ChatConsumerService chatService,
                         AnswerCacheService answerCacheService,
                         AnswerPersonalizationService personalizationCacheService,
                          ConversationValueService conversationValueService,
                          ConversationDocumentService conversationDocumentService) {
        this.chatService = chatService;
        this.answerCacheService = answerCacheService;
        this.personalizationCacheService = personalizationCacheService;
        this.conversationValueService = conversationValueService;
        this.conversationDocumentService = conversationDocumentService;
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
    public Mono<Map<String, Object>> chat(
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
        Mono<Map<String, Object>> resultMono;
        if (sessionId != null) {
            // ⭐ 有 sessionId：使用带 session 的完整响应
            log.info("[交互式多意图] 使用 session 模式: sessionId={}", sessionId);
            resultMono = Mono.fromCallable(() -> chatService.calculateWithSession(userId, message, sessionId, finalRequestId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(routerMap -> {
                        String resultText = (String) ((Map<String, Object>) routerMap).get("result");
                        String agentName = (String) ((Map<String, Object>) routerMap).get("agentName");
                        Boolean fromCache = (Boolean) ((Map<String, Object>) routerMap).getOrDefault("fromCache", false);
                        // ⭐ 提取 intentTag
                        String intentTag = (String) ((Map<String, Object>) routerMap).get("intentTag");
                        // ⭐ 提取工具调用信号（Router 根据实际执行链路设置）
                        Boolean toolInvoked = (Boolean) ((Map<String, Object>) routerMap).getOrDefault("toolInvoked", false);
                        Map<String, Object> map = new HashMap<>();
                        map.put("result", resultText != null ? resultText : "");
                        map.put("agentName", agentName);
                        map.put("fromCache", fromCache);
                        map.put("intentTag", intentTag);  // ⭐ 透传 intentTag
                        map.put("toolInvoked", toolInvoked);  // ⭐ 透传工具调用信号
                        return map;
                    });
        } else {
            // 4. 直接调用 LLM（无 session）
            log.info("[无Session] 直接调用 calculate");
            resultMono = Mono.fromCallable(() -> chatService.calculateWithSession(userId, message, null, finalRequestId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(routerMap -> {
                        String resultText = (String) routerMap.get("result");
                        String agentName = (String) routerMap.get("agentName");
                        Boolean fromCache = (Boolean) routerMap.getOrDefault("fromCache", false);
                        String intentTag = (String) routerMap.get("intentTag");
                        Boolean toolInvoked = (Boolean) routerMap.getOrDefault("toolInvoked", false);
                        Map<String, Object> map = new HashMap<>();
                        map.put("result", resultText != null ? resultText : "");
                        map.put("agentName", agentName);
                        map.put("fromCache", fromCache);
                        map.put("intentTag", intentTag);
                        map.put("toolInvoked", toolInvoked);
                        return map;
                    });
        }

        // 5. 组合所有异步操作
        return resultMono
            .flatMap(routerResponse -> {
                String reply = (String) routerResponse.get("result");

                // ⭐ 6. 解析 Agent 回复中的建议（以 ⭐ 开头的行为建议）
                List<String> suggestions = parseStarSuggestions(reply);
                
                // 从回复中移除建议行，避免重复展示
                String cleanReply = removeStarSuggestions(reply);

                // ⭐ 7. 对话价值评估：判断是否沉淀为用户个人文档（异步，不阻塞）
                if (sessionId != null) {
                    String agentName = (String) routerResponse.get("agentName");
                    Boolean fromCache = (Boolean) routerResponse.getOrDefault("fromCache", false);
                    // ⭐ 从透传的 intentTag 读取
                    String intentTag = (String) routerResponse.get("intentTag");
                    // ⭐ 计算当前轮数（session 粒度，使用 Caffeine asMap 的 merge 保证线程安全）
                    int turnCount = turnCounts.asMap().merge(sessionId, 1, Integer::sum);
                    // ⭐ 使用 Router 传来的 toolInvoked 信号（不再通过意图标签猜测）
                    Boolean toolInvoked = (Boolean) routerResponse.getOrDefault("toolInvoked", false);

                    ConversationValueService.ConversationValueContext ctx =
                            new ConversationValueService.ConversationValueContext(
                                    Long.valueOf(userId),
                                    sessionId,
                                    message + "\n" + cleanReply,
                                    agentName,
                                    intentTag,  // ⭐ 传递真实 intentTag
                                    turnCount, // ⭐ 传递真实轮数
                                    fromCache != null && fromCache,
                                    toolInvoked != null && toolInvoked  // ⭐ 使用 Router 传来的工具调用信号
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

                // 8. 构建响应
                Map<String, Object> response = new HashMap<>();
                response.put("reply", cleanReply);
                response.put("suggestions", suggestions);
                response.put("sessionId", sessionId);
                response.put("duration_ms", endTime - startTime);

                return Mono.just(response);
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
    public Mono<Map<String, Object>> getCacheStats(
            @RequestHeader(value = "X-User-Role", required = false) String userRoleFromHeader) {
        String role = (userRoleFromHeader != null) ? userRoleFromHeader : "ROLE_USER";
        if (!isAdmin(role)) {
            log.warn("[Auth] ⛔ 拒绝缓存统计请求: role={}", role);
            return Mono.just(buildForbiddenResponse("缓存统计功能仅限管理员使用"));
        }
        return answerCacheService.getCacheStats()
            .doOnNext(stats -> {
                log.info("[AnswerCache] 📊 缓存统计: {}", stats);
            });
    }

    /**
     * ⭐ 权限校验：是否为管理员
     */
    private boolean isAdmin(String role) {
        return "ROLE_ADMIN".equalsIgnoreCase(role);
    }

    /**
     * ⭐ 构建 403 Forbidden 响应体（舒缓语气 + 随机文案 + 智能建议）
     */
    private Map<String, Object> buildForbiddenResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        
        // 随机舒缓文案
        String[] friendlyMessages = {
            "✨ 这个问题我暂时无法回答你，不过你可以试试问问我其他问题呢？",
            "🌸 哎呀，这个问题有点超出我的能力范围了，换一个试试吧～",
            "🌻 我暂时还不会这个，不过我可以帮你查天气、找美食、做旅行规划哦！",
            "💫 这个问题我先记下来，你也可以先试试问我其他问题呢～",
            "🌈 这个我还在学习中！不过下面的问题我都可以帮你解答哦：",
            "🍀 换一个问题试试吧，比如查天气、推荐美食或者规划旅行都可以～",
            "🌺 我暂时帮不上这个忙，不过我有好多其他技能等你来发现呢！"
        };
        String friendly = friendlyMessages[new java.util.Random().nextInt(friendlyMessages.length)];
        
        // 建议列表：根据用户问题中的地点/意图生成上下文相关建议
        List<String> suggestions = new ArrayList<>();
        String lower = message != null ? message.toLowerCase() : "";
        boolean hasLocation = false;
        for (String city : new String[]{"北京", "上海", "广州", "深圳", "杭州", "成都", "南京", "西安", "重庆", "武汉"}) {
            if (lower.contains(city)) {
                hasLocation = true;
                suggestions.add(city + "今天天气怎么样？");
                suggestions.add(city + "有什么好吃的推荐？");
                suggestions.add(city + "有哪些必去的景点？");
                break;
            }
        }
        if (!hasLocation) {
            suggestions.add("北京今天天气怎么样？");
            suggestions.add("成都有什么美食推荐？");
            suggestions.add("杭州有哪些必去的景点？");
        }
        suggestions.add("帮我规划一次周末旅行");
        suggestions.add("今天适合出门走走吗？");
        
        response.put("reply", friendly);
        response.put("suggestions", suggestions);
        response.put("error", "FORBIDDEN");
        return response;
    }

}
