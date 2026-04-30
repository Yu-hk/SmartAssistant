package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.cache.AnswerCacheService;
import com.example.smartassistant.consumer.service.cache.AnswerPersonalizationService;
import com.example.smartassistant.consumer.service.core.MathConsumerService;
import com.example.smartassistant.consumer.service.data.HybridDataQueryService;
import com.example.smartassistant.consumer.service.recommendation.*;
import com.example.smartassistant.consumer.service.session.ChatMessageService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 智能对话 REST API - 纯代理模式
 * Consumer 只负责传递用户提问到 Router Service,不做任何处理
 */
@RestController
@RequestMapping("/api/math")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final MathConsumerService mathService;
    private final HybridDataQueryService hybridDataQueryService;  // ⭐ 新增：混合数据查询服务
    private final AnswerCacheService answerCacheService;  // ⭐ Phase 1: 答案缓存服务
    private final AnswerPersonalizationService personalizationCacheService;  // ⭐ 方案E: 缓存预热
    private final SuggestionEngine suggestionEngine;
    private final LLMSuggestionService llmSuggestionService;  // ⭐ Phase 1: LLM 智能建议
    private final UserProfileService userProfileService;       // ⭐ 用于获取用户画像
    private final ColdStartService coldStartService;
    private final ContextualRecommendationService contextualService;
    private final SuggestionPersonalizationService personalizationService;
    private final ChatMessageService chatMessageService;
    private final ChatClient chatClient;

    // ⭐ 建议数量限制配置
    @org.springframework.beans.factory.annotation.Value("${suggestion.max-count:5}")
    private int maxSuggestionCount;

    public ChatController(MathConsumerService mathService,
                         HybridDataQueryService hybridDataQueryService,
                         AnswerCacheService answerCacheService,
                         AnswerPersonalizationService personalizationCacheService,
                         SuggestionEngine suggestionEngine,
                         LLMSuggestionService llmSuggestionService,
                         UserProfileService userProfileService,
                         ColdStartService coldStartService,
                         ContextualRecommendationService contextualService,
                         SuggestionPersonalizationService personalizationService,
                         ChatMessageService chatMessageService,
                         ChatClient.Builder chatClientBuilder) {
        this.mathService = mathService;
        this.hybridDataQueryService = hybridDataQueryService;
        this.answerCacheService = answerCacheService;
        this.personalizationCacheService = personalizationCacheService;
        this.suggestionEngine = suggestionEngine;
        this.llmSuggestionService = llmSuggestionService;
        this.userProfileService = userProfileService;
        this.coldStartService = coldStartService;
        this.contextualService = contextualService;
        this.personalizationService = personalizationService;
        this.chatMessageService = chatMessageService;
        this.chatClient = chatClientBuilder.build();
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
            requestId = java.util.UUID.randomUUID().toString().replace("-", "");
        }
        final String finalRequestId = requestId;
        
        // ⭐ 方案E: 异步预热用户画像缓存（仅对已认证用户）
        if (!"anonymous".equals(userId) && !userIdFromHeader.isBlank()) {
            personalizationCacheService.preloadProfile(userId)
                .subscribe(
                    v -> log.debug("[CachePreload] ✅ 预热完成: userId={}", userId),
                    e -> log.warn("[CachePreload] ⚠️ 预热失败: {}", e.getMessage())
                );
        }
        
        // 2. 记录用户消息到对话历史(可选，失败不影响主流程)
        Mono<Void> saveUserMessageMono = Mono.empty();
        if (sessionId != null) {
            saveUserMessageMono = Mono.fromRunnable(() -> {
                try {
                    chatMessageService.saveUserMessage(sessionId, message);
                } catch (Exception e) {
                    log.warn("[MathController] ⚠️ 保存用户消息失败，继续处理: {}", e.getMessage());
                }
            });
        }
        
        // ⭐ 3. 智能判断是否为数据查询请求 / 交互式多意图
        Mono<Map<String, Object>> resultMono;
        if (isDataQueryRequest(message)) {
            // ⭐ 权限校验：数据查询仅限 ADMIN
            if (!isAdmin(role)) {
                log.warn("[Auth] ⛔ 拒绝数据查询请求: userId={}, role={}", userId, role);
                return Mono.just(buildForbiddenResponse("数据统计/查询功能仅限管理员使用"));
            }
            log.info("[数据查询] 检测到数据查询请求: {}", message);
            Map<String, Object> dataResult = new HashMap<>();
            dataResult.put("result", handleDataQuery(message));
            resultMono = Mono.just(dataResult);
        } else if (isDataQueryByLLM(message)) {
            // ⭐ LLM 增强判断：关键词未匹配但 LLM 识别为数据查询
            if (!isAdmin(role)) {
                log.info("[数据查询] LLM 识别为数据查询请求，非管理员拒绝: userId={}", userId);
                return Mono.just(buildForbiddenResponse("数据统计/查询功能仅限管理员使用"));
            }
            log.info("[数据查询] LLM 检测到数据查询请求: {}", message);
            Map<String, Object> dataResult = new HashMap<>();
            dataResult.put("result", handleDataQuery(message));
            resultMono = Mono.just(dataResult);
        } else if (sessionId != null) {
            // ⭐ 有 sessionId：直接调用带 session 的服务（缓存暂时禁用，排查 travel 回复问题）
            log.info("[交互式多意图] 使用 session 模式: sessionId={}", sessionId);
            resultMono = Mono.fromCallable(() -> mathService.calculateWithSession(userId, message, sessionId, finalRequestId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(reply -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("result", reply);
                        map.put("fromCache", false);
                        return map;
                    });
        } else {
            // 4. 直接调用 LLM（缓存暂时禁用，排查 travel 回复问题）
            log.info("[无Session] 直接调用 calculate（缓存已禁用）");
            resultMono = Mono.fromCallable(() -> mathService.calculate(userId, message, finalRequestId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(reply -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("result", reply);
                        map.put("fromCache", false);
                        return map;
                    });
        }

        // 5. 组合所有异步操作
        return saveUserMessageMono.then(resultMono)
            .flatMap(routerResponse -> {
                String reply = (String) routerResponse.get("result");

                // ⭐ 6. 智能建议：判断是否需要生成
                List<String> suggestions = shouldSkipSuggestions(message, reply) 
                    ? List.of() 
                    : generateSmartSuggestionsLLM(userId, message, sessionId, reply);

                // 7. 格式化建议并拼接到回复
                String formattedSuggestions = suggestionEngine.formatSuggestions(suggestions);
                String fullReply = reply + formattedSuggestions;

                // 8. 记录系统回复到对话历史(失败不影响主流程)
                if (sessionId != null) {
                    try {
                        chatMessageService.saveAiMessage(sessionId, fullReply, null, null);
                    } catch (Exception e) {
                        log.warn("[MathController] ⚠️ 保存 AI 回复失败，继续处理: {}", e.getMessage());
                    }
                }

                long endTime = System.currentTimeMillis();
                log.info("对话完成: userId={}, suggestions={}, duration={}ms",
                        userId,
                        suggestions.size(),
                        endTime - startTime);

                // 9. 构建响应
                Map<String, Object> response = new HashMap<>();
                response.put("reply", fullReply);
                response.put("suggestions", suggestions);
                response.put("sessionId", sessionId);
                response.put("duration_ms", endTime - startTime);

                return Mono.just(response);
            });
    }
    
    /**
     * ⭐ Phase 1: LLM 智能建议生成
     * 利用 ChatClient 根据对话上下文和用户画像生成语义化建议
     */
    private List<String> generateSmartSuggestionsLLM(String userId, String message,
                                                      String sessionId, String routerResult) {
        // 获取对话历史
        List<Map<String, String>> stringHistory = new ArrayList<>();
        if (sessionId != null) {
            List<Map<String, Object>> history = chatMessageService.getRecentMessages(sessionId, 10);
            for (Map<String, Object> msg : history) {
                Map<String, String> stringMsg = new HashMap<>();
                msg.forEach((k, v) -> stringMsg.put(k, v != null ? v.toString() : ""));
                stringHistory.add(stringMsg);
            }
        }

        // 获取用户画像（仅对已认证用户）
        String userProfile = null;
        if (!"anonymous".equals(userId) && userId != null) {
            try {
                Long userIdLong = parseUserId(userId);
                if (userIdLong != null) {
                    userProfile = userProfileService.buildUserProfilePrompt(userIdLong);
                }
            } catch (Exception e) {
                log.debug("[LLMSuggestion] 获取用户画像失败: {}", e.getMessage());
            }
        }

        // 调用 LLM 生成建议
        return llmSuggestionService.generateSuggestions(
                userId, message, stringHistory, userProfile, routerResult);
    }

    /**
     * 解析用户 ID（辅助方法）
     */
    private Long parseUserId(String userId) {
        if (userId == null || userId.isBlank() || "anonymous".equalsIgnoreCase(userId)) {
            return null;
        }
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 智能建议生成(分层策略) - 保留作为 LLM 降级方案
     */
    private List<String> generateSmartSuggestions(String userId, String message, String sessionId) {
        // ⭐ 如果是数据查询请求，使用专门的建议生成逻辑
        if (isDataQueryRequest(message)) {
            return generateDataQuerySuggestions(message);
        }

        List<String> suggestions;

        // Step 1: 获取对话历史
        List<Map<String, Object>> history = new ArrayList<>();
        if (sessionId != null) {
            history = chatMessageService.getRecentMessages(sessionId, 10);
        }

        // Step 2: 冷启动判断
        if (coldStartService.isColdStart(userId, history.size())) {
            log.debug("[Suggestion] 冷启动场景: userId={}", userId);
            String location = extractLocation(message);
            suggestions = coldStartService.generateColdStartSuggestions(location);
        }
        // Step 3: 上下文推荐(有历史对话)
        else if (!history.isEmpty()) {
            log.debug("[Suggestion] 上下文推荐: userId={}, historySize={}", userId, history.size());
            List<Map<String, String>> stringHistory = new ArrayList<>();
            for (Map<String, Object> msg : history) {
                Map<String, String> stringMsg = new HashMap<>();
                msg.forEach((k, v) -> stringMsg.put(k, v != null ? v.toString() : ""));
                stringHistory.add(stringMsg);
            }
            suggestions = contextualService.generateContextualSuggestions(message, stringHistory);
        }
        // Step 4: 策略模式推荐(无历史)
        else {
            log.debug("[Suggestion] 策略模式推荐: userId={}", userId);
            suggestions = suggestionEngine.generateSuggestions(message, "auto");
        }

        // Step 5: 个性化排序
        if (!suggestions.isEmpty() && !"anonymous".equals(userId)) {
            Map<String, String> intentMap = buildSuggestionIntentMap(suggestions);
            suggestions = personalizationService.personalizeSuggestions(userId, suggestions, intentMap);
        }

        // Step 6: 限制最多返回配置数量的建议
        if (suggestions.size() > maxSuggestionCount) {
            suggestions = suggestions.subList(0, maxSuggestionCount);
        }

        return suggestions;
    }
    
    /**
     * 从问题中提取地点
     */
    private String extractLocation(String question) {
        if (question == null) return null;
        
        Pattern pattern = Pattern.compile(
                "(广州|深圳|成都|杭州|南京|武汉|西安|长沙|青岛|厦门|三亚|"
                + "昆明|大理|丽江|桂林|苏州|无锡|宁波|哈尔滨|沈阳|大连|郑州|济南|"
                + "合肥|福州|南昌|贵阳|南宁|海口|拉萨|乌鲁木齐|兰州|西宁|银川|呼和浩特|"
                + "河北|河南|山东|山西|湖南|湖北|广东|广西|江苏|浙江|安徽|福建|江西|"
                + "四川|贵州|云南|陕西|甘肃|青海|黑龙江|吉林|辽宁|海南|台湾|内蒙古|"
                + "宁夏|新疆|西藏|北京|上海|天津|重庆)"
        );
        
        var matcher = pattern.matcher(question);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
    
    /**
     * 构建建议与意图的映射
     */
    private Map<String, String> buildSuggestionIntentMap(List<String> suggestions) {
        Map<String, String> intentMap = new HashMap<>();
        
        for (String suggestion : suggestions) {
            String lower = suggestion.toLowerCase();
            
            if (lower.contains("天气") || lower.contains("气温")) {
                intentMap.put(suggestion, "WEATHER");
            } else if (lower.contains("美食") || lower.contains("餐厅") || lower.contains("吃")) {
                intentMap.put(suggestion, "FOOD");
            } else if (lower.contains("旅游") || lower.contains("景点") || lower.contains("行程")) {
                intentMap.put(suggestion, "TRAVEL");
            } else if (lower.contains("交通") || lower.contains("地铁") || lower.contains("怎么去")) {
                intentMap.put(suggestion, "TRANSPORT");
            } else {
                intentMap.put(suggestion, "GENERAL");
            }
        }
        
        return intentMap;
    }

    /**
     * ⭐ 判断是否需要跳过智能建议
     */
    private boolean shouldSkipSuggestions(String message, String reply) {
        if (message == null || message.isBlank()) return true;
        if (reply == null || reply.isBlank()) return true;

        // 1. Agent 回复已自带建议（如 Travel/Food Agent 的 💡 智能建议）
        if (reply.contains("智能建议") || reply.contains("可以直接点击或回复序号")) {
            return true;
        }

        // 2. 简短消息（问候、感谢、单字回答 Agent 反问）
        String trimmed = message.trim();
        if (trimmed.length() <= 4) return true;

        // 3. 常见问候/感谢语
        String lower = trimmed.toLowerCase();
        if (greetingPattern.matcher(lower).matches()) return true;

        return false;
    }

    private static final java.util.regex.Pattern greetingPattern = java.util.regex.Pattern.compile(
        "^(你好|您好|hello|hi|hey|谢谢|感谢|好的|嗯|ok|好的吧|知道了|收到|再见|拜拜|bye)$"
    );

    /**
     * 判断是否为数据查询请求
     * 通过关键词识别数据查询意图
     */
    private boolean isDataQueryRequest(String message) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }
        
        String lowerMessage = message.toLowerCase();
        
        // ⭐ 扩展数据查询关键词（覆盖更多场景）
        String[] dataQueryKeywords = {
            // 统计类（高置信度，明确表示数据查询）
            "多少", "统计", "总数", "数量", "平均", "最大", "最小",
            "增长", "趋势", "分布", "排名", "排行", "占比", "比例",
            // SQL 关键词（明确表示数据查询）
            "count", "sum", "avg", "max", "min", "group by", "order by",
            "select", "from", "where"
        };
        
        // 先检查高置信度关键词
        for (String keyword : dataQueryKeywords) {
            if (lowerMessage.contains(keyword)) {
                return true;
            }
        }
        
        // ⭐ 对模糊匹配做二次验证：只有同时匹配多个指标关键词才认为是数据查询
        String[] fuzzyKeywords = {"列表", "字段", "结构", "前", "详情", "显示", "查看", "所有"};
        int fuzzyMatchCount = 0;
        for (String keyword : fuzzyKeywords) {
            if (lowerMessage.contains(keyword)) {
                fuzzyMatchCount++;
            }
        }
        // 需要至少命中 2 个模糊关键词才判定为数据查询
        return fuzzyMatchCount >= 2;
    }
    
    /**
     * ⭐ LLM 增强：判断用户问题是否为数据查询意图
     * <p>
     * 当关键词匹配未能命中时，用 LLM 做二次判断。
     * 仅对包含潜在数据查询关键词的问题调用，减少不必要的 LLM 调用。
     */
    private boolean isDataQueryByLLM(String message) {
        // 只对可能涉及数据查询的问题调用 LLM（减少无意义的 LLM 调用）
        if (message == null || message.length() < 4) return false;
        String lower = message.toLowerCase();
        if (!lower.contains("多少") && !lower.contains("用户") && !lower.contains("系统")
                && !lower.contains("几个") && !lower.contains("人数") && !lower.contains("数据")
                && !lower.contains("总数") && !lower.contains("所有") && !lower.contains("注册")
                && !lower.contains("查询") && !lower.contains("列表") && !lower.contains("统计")) {
            return false;
        }
        try {
            String result = chatClient.prompt()
                    .user("判断以下用户问题是查询系统数据（如用户数量、消息数量等），还是日常聊天。只回答 data_query 或 chat：\n" + message)
                    .call()
                    .content();
            if (result != null && result.trim().toLowerCase().contains("data_query")) {
                log.info("[LLM数据查询] 识别为数据查询: {}", message);
                return true;
            }
        } catch (Exception e) {
            log.warn("[LLM数据查询] LLM 判断失败: {}", e.getMessage());
        }
        return false;
    }
    
    /**
     * 为数据查询生成相关建议
     * 基于当前查询内容和常见数据分析场景
     */
    private List<String> generateDataQuerySuggestions(String currentQuery) {
        log.info("[数据查询建议] 开始生成建议，查询内容: {}", currentQuery);
        
        List<String> suggestions = new ArrayList<>();
        String query = currentQuery.toLowerCase();
        
        // 1. 基于关键词匹配提供相关建议
        if (query.contains("用户") || query.contains("user")) {
            suggestions.add("最近7天新增了多少用户？");
            suggestions.add("用户的地区分布如何？");
            suggestions.add("活跃用户占比多少？");
        } else if (query.contains("消息") || query.contains("聊天") || query.contains("message")) {
            suggestions.add("哪个时间段消息最多？");
            suggestions.add("平均每个会话有多少条消息？");
            suggestions.add("消息类型分布如何？");
        } else if (query.contains("路由") || query.contains("route")) {
            suggestions.add("各个 Agent 的调用次数统计");
            suggestions.add("路由成功率是多少？");
            suggestions.add("平均响应时间分析");
        } else if (query.contains("统计") || query.contains("总数") || query.contains("多少")) {
            suggestions.add("按时间维度查看趋势");
            suggestions.add("与其他指标对比分析");
            suggestions.add("查看详细信息 breakdown");
        }
        
        // 2. 如果没有匹配到特定场景，提供通用建议
        if (suggestions.isEmpty()) {
            suggestions.add("查看数据库表结构");
            suggestions.add("探索其他可查询的数据");
            suggestions.add("尝试更具体的查询条件");
        }
        
        // 3. 限制建议数量（数据查询建议更少但更精准）

        log.debug("[数据查询建议] 生成了 {} 条建议", suggestions.size());
        return suggestions;
    }
    
    /**
     * ⭐ Phase 1: 带缓存的答案获取（完全响应式）
     * 先检查 Redis 缓存，未命中再调用 LLM
     * 
     * @param userId 用户ID
     * @param message 消息
     * @param isFirstFetch 是否是首次获取（用于标记来自语义缓存的结果）
     */
    private Mono<String> getAnswerWithCacheReactive(String userId, String message, boolean isFirstFetch) {
        return answerCacheService.getAnswerWithCache(
            userId,
            message,
            isFirstFetch,  // ⭐ 传递是否为首次获取
            () -> {
                // 缓存未命中时，调用 MathConsumerService
                log.debug("[AnswerCache] 缓存未命中，调用 LLM, userId={}", userId);
                return Mono.just(mathService.calculate(userId, message));
            }
        );
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
     * 处理数据查询请求
     * 使用 HybridDataQueryService 智能选择 MyBatis 或 MCP
     */
    private String handleDataQuery(String message) {
        try {
            // 调用混合查询服务，自动选择最优方式
            Map<String, Object> result = hybridDataQueryService.naturalLanguageQuery(message);
            
            if (Boolean.TRUE.equals(result.get("success"))) {
                String answer = (String) result.get("answer");
                String method = (String) result.get("method");
                
                log.info("[数据查询] 完成: method={}", method);
                
                // 直接返回答案，不添加调试信息
                return answer;
            } else {
                log.warn("[数据查询] 失败: {}", result.get("error"));
                return "抱歉，暂时无法完成数据查询。请稍后重试或使用其他方式查询。";
            }
            
        } catch (Exception e) {
            log.error("[数据查询] 异常", e);
            return "数据查询出错：" + e.getMessage();
        }
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
