package com.example.smartassistant.router.service;

import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.model.RouteDecision;
import com.example.smartassistant.router.model.RouteRequest;
import com.example.smartassistant.router.model.RoutingResult;
import com.example.smartassistant.router.strategy.RoutingStrategyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Router Service - 核心路由服务（单意图路由）
 *
 * <p>职责范围：</p>
 * <ul>
 *     <li>单意图路由与 Agent 调用</li>
 *     <li>降级方案：关键词匹配</li>
 * </ul>
 *
 * <p>已移除职责（迁移到 Consumer）：</p>
 * <ul>
 *     <li>日常建议生成 → Consumer.LLMSuggestionService</li>
 *     <li>多意图识别与处理 → 不再支持，只处理第一个意图</li>
 * </ul>
 */
@Service
public class RouterService {
    
    private static final Logger log = LoggerFactory.getLogger(RouterService.class);
    
    private final RoutingStrategyManager strategyManager;
    private final AgentCallerService agentCallerService;
    private final AgentDiscoveryService agentDiscoveryService;
    private final ChatClient chatClient;
    private final StringRedisTemplate redisTemplate;
    private final RouterRagService ragService;
    private final SemanticRouteCacheService semanticCache;

    @Value("${router.agent.rag.enabled:false}")
    private boolean ragEnabled;

    @Value("${router.context.history.max-messages:10}")
    private int maxHistoryMessages;

    // ⭐ 任务拆分配置
    @Value("${router.task-splitting.enabled:true}")
    private boolean taskSplittingEnabled;
    
    @Value("${router.task-splitting.max-sub-tasks:3}")
    private int maxSubTasks;

    public RouterService(RoutingStrategyManager strategyManager,
                        AgentCallerService agentCallerService,
                        AgentDiscoveryService agentDiscoveryService,
                        ChatClient.Builder chatClientBuilder,
                        @Qualifier("routerParallelAgentExecutor") Executor routerParallelAgentExecutor,
                        @Autowired(required = false) StringRedisTemplate redisTemplate,
                        RouterRagService ragService,
                        SemanticRouteCacheService semanticCache) {
        this.strategyManager = strategyManager;
        this.agentCallerService = agentCallerService;
        this.agentDiscoveryService = agentDiscoveryService;
        this.chatClient = chatClientBuilder.build();
        this.redisTemplate = redisTemplate;
        this.ragService = ragService;
        this.semanticCache = semanticCache;
    }
    
    /**
     * 执行路由决策并调用目标 Agent（统一入口）
     * 只识别并处理第一个意图
     */
    public RoutingResult route(RouteRequest request) {
        log.info("[Router] 收到路由请求: userId={}, sessionId={}, question={}",
                request.getUserId(), request.getSessionId(), truncate(request.getQuestion(), 100));

        try {
            // Step 1: 检查语义缓存（仅存 agent 名，不存回复内容）
            String question = request.getQuestion();
            SemanticRouteCacheService.CachedRouteDecision cached = semanticCache.getCachedDecision(question);
            
            // Step 2: 构建上下文
            Map<String, Object> context = buildContext(request);

            // Step 3: RAG 增强(可选)
            String enhancedQuestion = question;
            if (ragEnabled && Boolean.TRUE.equals(request.getEnableRag())) {
                @SuppressWarnings("unchecked")
                List<String> history = (List<String>) context.get("conversationHistory");
                enhancedQuestion = ragService.enhanceQuestion(
                        request.getQuestion(),
                        history
                );
            }

            Long userId = request.getUserId();

            // Step 4: 单意图路由（语义缓存命中时直接返回缓存回复）
            RoutingResult result;
            if (cached != null) {
                log.info("[Router] ⚡ 语义缓存命中: intent={}, agent={}, hit={}", cached.intentTag, cached.agentName, cached.hitCount);
                if (cached.reply != null && !cached.reply.isBlank()) {
                    // ⭐ 有缓存回复，直接返回（加变化前缀避免重复）
                    String wrapped = semanticCache.wrapCachedReply(cached.reply, cached, request.getQuestion());
                    result = RoutingResult.builder()
                            .result(wrapped)
                            .agentName(cached.agentName)
                            .confidence(cached.confidence)
                            .intentTag(cached.intentTag)  // ⭐ 从缓存中恢复 intentTag
                            .build();
                } else {
                    // 仅有路由决策无回复内容，仍需调用 Agent
                    result = RoutingResult.builder()
                            .result("")
                            .agentName(cached.agentName)
                            .confidence(cached.confidence)
                            .intentTag(cached.intentTag)  // ⭐ 从缓存中恢复 intentTag
                            .build();
                    if (cached.agentName != null && !"none".equals(cached.agentName)) {
                        String agentReply = agentCallerService.callAgent(cached.agentName, enhancedQuestion, userId);
                        if (agentReply != null) {
                            result.setResult(agentReply);
                        }
                    }
                }
            } else if (taskSplittingEnabled) {
                // ⭐ 任务拆解：检测并执行多意图
                List<String> subTasks = trySplitIntents(enhancedQuestion);
                if (subTasks.size() > 1) {
                    log.info("[Router] 🔀 检测到多意图，拆分为 {} 个子任务: {}", subTasks.size(), subTasks);
                    result = executeMultiIntent(subTasks, context, userId, request.getRequestId());
                } else {
                    result = handleSingleIntent(enhancedQuestion, context, userId);
                }
            } else {
                result = handleSingleIntent(enhancedQuestion, context, userId);
            }

            // ⭐ 生成意图标签（用于用户画像统计），设置到 result
            String intentTag = semanticCache.generateIntentTag(question);
            result.setIntentTag(intentTag);
            
            // ⭐ 缓存路由决策（传入 requestId 写入审计日志）+ 异步缓存回复
            String agentName = result.getAgentName();
            String requestId = request.getRequestId();
            if (agentName != null && !"none".equals(agentName) && !agentName.isBlank()) {
                // 缓存路由决策（含审计日志）
                semanticCache.saveDecision(requestId, question, agentName, result.getConfidence(), userId);

                // 缓存回复内容（Agent 返回后异步写入，不阻塞主流程）
                String reply = result.getResult();
                if (cached == null || cached.reply == null) {
                    // 首次路由或 Agent 重新调用：缓存回复原文
                    if (reply != null && !reply.isBlank() && !reply.startsWith("❌")) {
                        semanticCache.saveReply(question, reply, agentName);
                    }
                }
                // 缓存命中时已有回复缓存，无需重复写入
            }

            // ⭐ 将完整决策写入 Redis，供 Consumer 阻塞读取（含 intentTag）
            if (requestId != null && !requestId.isBlank() && agentName != null) {
                semanticCache.saveFullDecisionForConsumer(requestId, agentName,
                        result.getConfidence(), result.getResult(), result.getIntentTag());
            }
            
            return result;

        } catch (Exception e) {
            log.error("[Router] 路由失败: {}", e.getMessage(), e);
            return RoutingResult.builder()
                    .result("❌ 路由失败: " + e.getMessage())
                    .build();
        }
    }
    
    /**
     * 处理单意图场景（原有逻辑）
     * ⭐ 复用 ReactAgent 已提取的结构化信息，避免二次解析
     * @return RoutingResult 包含真实的 agentName、confidence 和 Agent 执行结果
     */
    private RoutingResult handleSingleIntent(String question, Map<String, Object> context, Long userId) {
        // 使用策略管理器进行路由决策
        RouteDecision decision = strategyManager.executeRouting(question, context);

        // 检查是否成功路由到 Agent
        if (decision == null) {
            log.warn("[Router] 策略路由失败，尝试关键词降级");
            String keywordAgent = keywordBasedAgentSelection(question);
            if (keywordAgent != null) {
                log.info("[Router] 关键词降级成功: agent={}", keywordAgent);
                decision = RouteDecision.builder()
                        .agentName(keywordAgent)
                        .confidence(0.5)
                        .routingMethod("KEYWORD_FALLBACK")
                        .build();
            } else {
                log.warn("[Router] 无专业 Agent 匹配，尝试降级 Agent");
                
                // Layer 1: 通过 A2A 调用通用的降级 Agent（如 general_chat）
                try {
                    DiscoveredAgent fallbackAgent = agentDiscoveryService.findFallbackAgent();
                    if (fallbackAgent != null) {
                        String fallbackReply = agentCallerService.callAgent(
                                fallbackAgent.getAgentName(), question, userId);
                        
                        if (fallbackReply != null && !fallbackReply.isBlank() && !fallbackReply.startsWith("❌")) {
                            return RoutingResult.builder()
                                    .result(fallbackReply)
                                    .agentName(fallbackAgent.getAgentName())
                                    .confidence(0.3)
                                    .build();
                        }
                    }
                } catch (Exception e) {
                    log.warn("[Router] 降级 Agent 调用失败: {}", e.getMessage());
                }
                
                // Layer 2: Router 内联 ChatClient 终极兜底（Nacos/Agent 不可用时）
                try {
                    String localReply = chatClient.prompt()
                            .user(question)
                            .call()
                            .content();
                    
                    if (localReply != null && !localReply.isBlank()) {
                        return RoutingResult.builder()
                                .result(localReply)
                                .agentName("builtin_fallback")
                                .confidence(0.2)
                                .build();
                    }
                } catch (Exception e) {
                    log.warn("[Router] 内联 ChatClient 兜底失败: {}", e.getMessage());
                }
                
                // 所有兜底都失败时的最终提示
                return RoutingResult.builder()
                        .result("😅 啊哦，好像出了点小状况，我暂时没能好好回应你的问题。不过别担心，你可以试着问问关于美食或者旅行方面的问题，那些我可熟悉啦！")
                        .agentName("none")
                        .confidence(0.0)
                        .build();
            }
        }

        log.info("[Router] 路由决策: agent={}, confidence={}, method={}",
                decision.getAgentName(), decision.getConfidence(), decision.getRoutingMethod());

        // ⭐ 将 ReactAgent 提取的结构化上下文注入到 context，供后续使用
        if (decision.getExtractedContext() != null) {
            var extracted = decision.getExtractedContext();
            context.put("extractedLocation", extracted.getLocation());
            context.put("extractedIntent", extracted.getIntent());
            context.put("extractedTimeRange", extracted.getTimeRange());
            log.debug("[Router] 复用 ReactAgent 提取结果: location={}, intent={}, timeRange={}",
                    extracted.getLocation(), extracted.getIntent(), extracted.getTimeRange());
        }

        // 调用目标 Agent
        String result;
        if (decision.getExtractedContext() != null) {
            // 使用带上下文的调用
            result = agentCallerService.callAgentWithContext(
                    decision.getAgentName(),
                    question,
                    userId,
                    decision.getExtractedContext()
            );
        } else {
            // 使用普通调用
            result = agentCallerService.callAgent(
                    decision.getAgentName(),
                    question,
                    userId
            );
        }

        log.info("[Router] 路由完成: agent={}, resultLength={}",
                decision.getAgentName(), result != null ? result.length() : 0);

        // ⭐ 返回包含真实 decision 信息的结果
        return RoutingResult.builder()
                .result(result)
                .agentName(decision.getAgentName())
                .confidence(decision.getConfidence())
                .build();
    }
    
/**
     * 为指定意图选择最佳 Agent
     */
    private String selectBestAgent(String question, Map<String, Object> context) {
        // 复用现有的策略管理器
        var decision = strategyManager.executeRouting(question, context);
        
        if (decision != null) {
            return decision.getAgentName();
        }
        
        // 降级：基于关键词匹配
        return keywordBasedAgentSelection(question);
    }
    
    /**
     * 基于关键词的 Agent 选择（降级方案）
     */
    private String keywordBasedAgentSelection(String question) {
        try {
            DiscoveredAgent matched = agentDiscoveryService.matchAgent(question);
            if (matched != null) {
                log.info("[Router] 动态发现匹配 Agent: {} (类型: {})", 
                        matched.getServiceName(), matched.getAgentName());
                return matched.getAgentName();
            }
        } catch (Exception e) {
            log.warn("[Router] 动态匹配 Agent 失败: {}", e.getMessage());
        }
        
        // 降级：返回第一个可用 Agent
        var agents = agentDiscoveryService.discoverAllAgents();
        if (!agents.isEmpty()) {
            return agents.get(0).getAgentName();
        }
        
        return null;
    }

    /**
     * ⭐ 任务拆解：检测多意图并拆分为子任务
     * <p>
     * 用分隔符（？?和以及,等）分割用户问题。
     * 每个子任务独立路由和执行，最后用 LLM 汇总。
     */
    private List<String> trySplitIntents(String question) {
        if (question == null || question.isBlank()) return Collections.singletonList(question);
        List<String> parts = new ArrayList<>();
        // 中文疑问句分隔符
        String[] separators = {"？", "?", "  ", "。", "！"};
        String current = question;
        for (String sep : separators) {
            List<String> split = new ArrayList<>();
            for (String part : current.split(Pattern.quote(sep))) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) split.add(trimmed);
            }
            if (split.size() > 1) {
                current = String.join(" ", split);
                parts = split;
                break;
            }
        }
        // 尝试通过"和""以及"在意图关键词处分割
        if (parts.size() <= 1 && question.matches(".*(天气|美食|旅游|景点).*(和|以及|与).*(天气|美食|旅游|景点).*")) {
            String[] kwSplit = question.split("(和|以及|与)");
            for (String s : kwSplit) {
                String t = s.trim();
                if (!t.isEmpty()) parts.add(t);
            }
        }
        if (parts.size() <= 1) return Collections.singletonList(question);
        return parts.stream().limit(maxSubTasks).toList();
    }

    private static final String SSE_EVENTS_KEY_PREFIX = "routing:sse:events:";
    private static final long SSE_EVENTS_TTL_SECONDS = 120;

    /**
     * ⭐ 多意图顺序执行 + SSE 事件存储
     * <p>
     * 顺序执行每个子任务，同时将推理过程写入 Redis（供 Consumer SSE 接口读取）。
     * 支持前端依次展示每个 Agent 的推理过程。
     */
    private RoutingResult executeMultiIntent(List<String> subTasks, Map<String, Object> context, Long userId, String requestId) {
        List<SubTaskResult> subResults = new ArrayList<>();
        String firstAgent = null;
        String eventsKey = requestId != null ? SSE_EVENTS_KEY_PREFIX + requestId : null;

        for (String subTask : subTasks) {
            log.info("[Router] 🔀 执行子任务: {}", subTask);
            // 存储 routed 事件
            String detectedAgent = selectBestAgent(subTask, context);
            storeSseEvent(eventsKey, "routed", "🎯 正在处理: " + subTask, detectedAgent);

            RoutingResult subResult = handleSingleIntent(subTask, context, userId);
            subResults.add(new SubTaskResult(subTask, subResult.getResult(), subResult.getAgentName()));
            if (firstAgent == null) firstAgent = subResult.getAgentName();

            // 存储 tool_call + response 事件
            storeSseEvent(eventsKey, "tool_call", subTask, subResult.getAgentName());
            if (subResult.getResult() != null && !subResult.getResult().isBlank()) {
                storeSseEvent(eventsKey, "response",
                        subResult.getResult().substring(0, Math.min(200, subResult.getResult().length())),
                        subResult.getAgentName());
            }
        }

        // 存储 summarizing 事件
        storeSseEvent(eventsKey, "summarizing", "正在汇总多源信息...", null);

        String aggregated = aggregateResults(subTasks.get(0), subResults);
        log.info("[Router] 🔀 任务拆解完成: {} 个子任务, 汇总长度={}", subResults.size(), aggregated.length());

        return RoutingResult.builder()
                .result(aggregated)
                .agentName(firstAgent != null ? firstAgent : "none")
                .confidence(0.7)
                .build();
    }

    /**
     * ⭐ 存储 SSE 事件到 Redis（供 Consumer 读取并转发给前端）
     */
    private void storeSseEvent(String eventsKey, String type, String content, String agent) {
        if (eventsKey == null || redisTemplate == null) return;
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\"type\":\"").append(type).append("\"");
            if (content != null) {
                json.append(",\"content\":\"").append(escapeJson(content)).append("\"");
            }
            if (agent != null) {
                json.append(",\"agent\":\"").append(agent).append("\"");
            }
            json.append("}");
            redisTemplate.opsForList().rightPush(eventsKey, json.toString());
            redisTemplate.expire(eventsKey, SSE_EVENTS_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[Router] 存储 SSE 事件失败: {}", e.getMessage());
        }
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * ⭐ 用 LLM 汇总多个子任务的结果
     */
    private String aggregateResults(String originalQuestion, List<SubTaskResult> subResults) {
        if (subResults.isEmpty()) return "";
        if (subResults.size() == 1) return subResults.get(0).result;

        try {
            StringBuilder prompt = new StringBuilder("用户同时问了以下问题，请综合这些信息给出一个完整的回复：\n\n");
            for (int i = 0; i < subResults.size(); i++) {
                SubTaskResult sub = subResults.get(i);
                prompt.append("问题").append(i + 1).append("：").append(sub.question).append("\n");
                prompt.append("回答").append(i + 1).append("：").append(sub.result).append("\n\n");
            }
            prompt.append("请将以上信息整合成一段通顺自然的回复，不要分段列出各个问题。");
            return chatClient.prompt().user(prompt.toString()).call().content();
        } catch (Exception e) {
            log.warn("[Router] LLM 汇总失败，降级到拼接: {}", e.getMessage());
            StringBuilder fallback = new StringBuilder();
            for (SubTaskResult sub : subResults) {
                if (sub.result != null && !sub.result.isBlank()) {
                    fallback.append(sub.result).append("\n\n");
                }
            }
            return fallback.toString().trim();
        }
    }

    /**
     * 子任务结果
     */
    private static class SubTaskResult {
        final String question;
        final String result;
        final String agentName;
        SubTaskResult(String question, String result, String agentName) {
            this.question = question; this.result = result; this.agentName = agentName;
        }
    }

    /**
     * 提取共享上下文
     * ⭐ 优先复用 ReactAgent 已提取的结构化信息，避免二次解析
     */
    private RouteDecision.ExtractedContext extractSharedContext(String userInput, Map<String, Object> context) {
        // 优先从 context 中获取 ReactAgent 已提取的信息
        String location = (String) context.get("extractedLocation");
        String time = (String) context.get("extractedTimeRange");

        // 如果 ReactAgent 没有提取到，才降级到正则提取
        if (location == null) {
            location = extractLocationFallback(userInput);
        }
        if (time == null) {
            time = extractTimeFallback(userInput);
        }

        return new RouteDecision.ExtractedContext(location, time, null, null);
    }

    /**
     * 提取地点信息（降级方案）
     * 仅当 ReactAgent 未提取到地点时使用
     */
    private String extractLocationFallback(String text) {
        // 简单实现：匹配常见城市名
        Pattern pattern = Pattern.compile("(北京|上海|广州|深圳|杭州|南京|成都|重庆|西安|武汉)");
        Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    /**
     * 提取时间信息（降级方案）
     * 仅当 ReactAgent 未提取到时间时使用
     */
    private String extractTimeFallback(String text) {
        if (text.contains("明天")) return "明天";
        if (text.contains("后天")) return "后天";
        if (text.contains("周末")) return "周末";
        if (text.contains("下周")) return "下周";

        return null;
    }
    
    /**
     * 构建上下文
     * ⭐ 从 Redis 加载会话历史，提升多轮对话的路由准确性
     */
    private Map<String, Object> buildContext(RouteRequest request) {
        Map<String, Object> context = new HashMap<>();
        String sessionId = request.getSessionId();
        if (sessionId != null) {
            context.put("sessionId", sessionId);
        }
        if (request.getUserId() != null) {
            context.put("userId", request.getUserId());
        }

        // ⭐ 从 Redis 加载会话上下文（最近 N 条消息）
        loadConversationHistoryFromRedis(context, sessionId);

        return context;
    }

    /**
     * 从 Redis 加载会话历史消息
     */
    private void loadConversationHistoryFromRedis(Map<String, Object> context, String sessionId) {
        if (redisTemplate == null || sessionId == null || sessionId.isBlank()) {
            return;
        }

        try {
            String historyKey = "chat:history:" + sessionId;
            Long size = redisTemplate.opsForList().size(historyKey);

            if (size == null || size == 0) {
                log.debug("[Router] Redis 中无会话历史: sessionId={}", sessionId);
                return;
            }

            // 读取最近 N 条消息
            int start = 0;
            int end = Math.min(maxHistoryMessages - 1, size.intValue() - 1);
            List<String> history = redisTemplate.opsForList().range(historyKey, start, end);

            if (history != null && !history.isEmpty()) {
                context.put("conversationHistory", history);
                log.debug("[Router] 从 Redis 加载会话历史: sessionId={}, messages={}",
                        sessionId, history.size());
            }
        } catch (Exception e) {
            log.warn("[Router] 从 Redis 加载会话历史失败: sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
    }
    
/**
    private static class ExtractedContext {
        String location;
        String time;
        
        ExtractedContext(String location, String time) {
            this.location = location;
            this.time = time;
        }
    }
    
/**
     * ⭐ 截断字符串（用于日志）
     * 已提升为 public static，供外部服务使用
     */
    public static String truncate(String str, int maxLength) {
        if (str == null) return "";
        return str.length() > maxLength ? str.substring(0, maxLength) + "..." : str;
    }
}
