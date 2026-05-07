package com.example.smartassistant.consumer.controller;

import com.example.smartassistant.consumer.service.cache.AnswerCacheService;
import com.example.smartassistant.consumer.service.cache.AnswerPersonalizationService;
import com.example.smartassistant.consumer.service.core.MathConsumerService;
import com.example.smartassistant.consumer.service.data.HybridDataQueryService;
import com.example.smartassistant.consumer.service.session.ChatMessageService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;

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
    private final ChatMessageService chatMessageService;
    private final ChatClient chatClient;

    public ChatController(MathConsumerService mathService,
                         HybridDataQueryService hybridDataQueryService,
                         AnswerCacheService answerCacheService,
                         AnswerPersonalizationService personalizationCacheService,
                          ChatMessageService chatMessageService,
                         ChatClient.Builder chatClientBuilder) {
        this.mathService = mathService;
        this.hybridDataQueryService = hybridDataQueryService;
        this.answerCacheService = answerCacheService;
        this.personalizationCacheService = personalizationCacheService;
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
            requestId = UUID.randomUUID().toString().replace("-", "");
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
            // ⭐ 有 sessionId：使用带 session 的完整响应
            log.info("[交互式多意图] 使用 session 模式: sessionId={}", sessionId);
            resultMono = Mono.fromCallable(() -> mathService.calculateWithSession(userId, message, sessionId, finalRequestId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(routerMap -> {
                        String resultText = (String) ((Map<String, Object>) routerMap).get("result");
                        String agentName = (String) ((Map<String, Object>) routerMap).get("agentName");
                        Map<String, Object> map = new HashMap<>();
                        map.put("result", resultText != null ? resultText : "");
                        map.put("agentName", agentName);
                        map.put("fromCache", false);
                        return map;
                    });
        } else {
            // 4. 直接调用 LLM（无 session）
            log.info("[无Session] 直接调用 calculate");
            resultMono = Mono.fromCallable(() -> mathService.calculateWithSession(userId, message, null, finalRequestId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(routerMap -> {
                        String resultText = (String) ((Map<String, Object>) routerMap).get("result");
                        String agentName = (String) ((Map<String, Object>) routerMap).get("agentName");
                        Map<String, Object> map = new HashMap<>();
                        map.put("result", resultText != null ? resultText : "");
                        map.put("agentName", agentName);
                        map.put("fromCache", false);
                        return map;
                    });
        }

        // 5. 组合所有异步操作
        return saveUserMessageMono.then(resultMono)
            .flatMap(routerResponse -> {
                String reply = (String) routerResponse.get("result");

                // ⭐ 6. 解析 Agent 回复中的建议（以 ⭐ 开头的行为建议）
                List<String> suggestions = parseStarSuggestions(reply);
                
                // 从回复中移除建议行，避免重复展示
                String cleanReply = removeStarSuggestions(reply);
                
                // 7. 记录系统回复到对话历史(失败不影响主流程)
                if (sessionId != null) {
                    try {
                        chatMessageService.saveAiMessage(sessionId, cleanReply, null, null);
                    } catch (Exception e) {
                        log.warn("[ChatController] ⚠️ 保存 AI 回复失败，继续处理: {}", e.getMessage());
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
    
    /**
     * ⭐ Phase 1: LLM 智能建议生成
     * 利用 ChatClient 根据对话上下文和用户画像生成语义化建议
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
