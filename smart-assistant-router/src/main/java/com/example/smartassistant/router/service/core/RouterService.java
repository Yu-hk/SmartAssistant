/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.model.RouteDecision;
import com.example.smartassistant.router.model.RouteRequest;
import com.example.smartassistant.router.model.RoutingResult;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import com.example.smartassistant.router.service.cache.SemanticRouteCacheService;
import com.example.smartassistant.router.service.rag.RouterRagService;
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
import java.util.concurrent.atomic.AtomicInteger;
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

    // ⭐ 多套兜底说辞轮换
    private static final List<String> FALLBACK_MESSAGES = List.of(
            "😅 抱歉让你等了这么久，目前服务似乎遇到了一些临时问题。请稍后再试一下，或者联系技术支持看看。谢谢你的耐心！",
            "🙏 不好意思让你久等了，系统这会儿有点忙不过来，暂时没办法回应你的问题。过一会儿再找我试试吧！",
            "🤗 哎呀，好像出了点小岔子……你先别着急，我这边正在努力恢复中，等一小会儿再来找我聊聊好吗？",
            "😊 真抱歉，刚才没能帮上忙。系统可能在打盹儿，你先去喝杯水，待会儿再来找我试试看？"
    );
    private final AtomicInteger fallbackIndex = new AtomicInteger(0);

    public RouterService(AgentCallerService agentCallerService,
                        AgentDiscoveryService agentDiscoveryService,
                        ChatClient.Builder chatClientBuilder,
                        @Qualifier("routerParallelAgentExecutor") Executor routerParallelAgentExecutor,
                        @Autowired(required = false) StringRedisTemplate redisTemplate,
                        RouterRagService ragService,
                        SemanticRouteCacheService semanticCache) {
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
                    String wrapped = semanticCache.wrapCachedReply(cached.reply, cached, request.getQuestion(), request.getUserId());
                    result = RoutingResult.builder()
                            .result(wrapped)
                            .agentName(cached.agentName)
                            .confidence(cached.confidence)
                            .intentTag(cached.intentTag)  // ⭐ 从缓存中恢复 intentTag
                            .fromCache(true)  // ⭐ 标记为缓存命中，Consumer 据此跳过文档沉淀
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
            
            // ⭐ 提取原始问题（去除 Prompt 模板标记），用于缓存 key 生成
            String rawQuestion = extractRawQuestion(question);
            
            // ⭐ 缓存路由决策 + 回复（使用原始问题 + 已生成的 intentTag）
            String agentName = result.getAgentName();
            String requestId = request.getRequestId();
            if (agentName != null && !"none".equals(agentName) && !agentName.isBlank()) {
                // 缓存路由决策（含审计日志）
                semanticCache.saveDecision(requestId, question, agentName, result.getConfidence(), userId, intentTag, request.getSessionId());
                // 更新精确匹配为原始问题的 MD5（覆盖带历史计数的全 Prompt MD5）
                semanticCache.saveExactMatch(rawQuestion, intentTag);

                // 缓存回复内容（Agent 返回后异步写入，不阻塞主流程）
                String reply = result.getResult();
                if (cached == null || cached.reply == null) {
                    // 首次路由或 Agent 重新调用：缓存回复原文
                    if (reply != null && !reply.isBlank() && !reply.startsWith("❌")) {
                        semanticCache.saveReply(question, reply, agentName, intentTag);
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
     * 处理单意图场景（纯关键词路由 + 兜底链路）
     * 路由顺序：关键词匹配 → Fallback Agent(priority) → 内联 ChatClient → 最终提示
     */
    private RoutingResult handleSingleIntent(String question, Map<String, Object> context, Long userId) {
        // Step 1: 基于 Agent 注册关键词匹配
        String matchedAgent = keywordBasedAgentSelection(question);
        if (matchedAgent != null) {
            log.info("[Router] 关键词路由成功: agent={}", matchedAgent);
            String result = agentCallerService.callAgent(matchedAgent, question, userId);
            return RoutingResult.builder()
                    .result(result)
                    .agentName(matchedAgent)
                    .confidence(0.5)
                    .build();
        }
        
        // Step 2: 无专业 Agent 匹配，尝试降级 Fallback Agent（基于 metadata priority）
        DiscoveredAgent fallbackAgent = null;
        try {
            fallbackAgent = agentDiscoveryService.findFallbackAgent();
        } catch (Exception e) {
            log.warn("[Router] 查找 Fallback Agent 失败: {}", e.getMessage());
        }
        if (fallbackAgent != null) {
            try {
                String fallbackReply = agentCallerService.callAgent(
                        fallbackAgent.getAgentName(), question, userId);
                if (fallbackReply != null && !fallbackReply.isBlank() && !fallbackReply.startsWith("❌")) {
                    return RoutingResult.builder()
                            .result(fallbackReply)
                            .agentName(fallbackAgent.getAgentName())
                            .confidence(0.3)
                            .build();
                }
            } catch (Exception e) {
                log.warn("[Router] Fallback Agent 调用失败: {}", e.getMessage());
            }
        }
        
        // Layer 3: 内联 ChatClient 终极兜底
        try {
            String localReply = chatClient.prompt()
                    .system("你是一个温暖、耐心的助手。用户已经等待了一段时间，可能有些着急了。"
                          + "请用温和友善的语气回应，先为等待道歉，然后安抚情绪。"
                          + "如果实在无法处理当前问题，诚恳地请用户稍后再试，"
                          + "不要引导用户去尝试其他功能（因为那些功能可能也暂时不可用）。")
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
        int idx = fallbackIndex.getAndUpdate(i -> (i + 1) % FALLBACK_MESSAGES.size());
        return RoutingResult.builder()
                .result(FALLBACK_MESSAGES.get(idx))
                .agentName("none")
                .confidence(0.0)
                .build();
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
        
        // 降级：使用 fallback Agent（基于 metadata priority）
        var fallbackAgent = agentDiscoveryService.findFallbackAgent();
        if (fallbackAgent != null) {
            log.info("[Router] 关键词兜底匹配到 fallback Agent: {}", fallbackAgent.getAgentName());
            return fallbackAgent.getAgentName();
        }
        
        // ⚠️ 不降级到任意可用 Agent。matchAgent + fallbackAgent 均未匹配时，
        // 返回 null 让 handleSingleIntent() 走自然兜底链（内联 ChatClient → 最终提示）
        // 避免路由到不相关的 Agent 产生误导性回复
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
        
        // 常见疑问句分隔符 + 意图连接词
        List<String> parts = new ArrayList<>();
        String[] separators = {"？", "?", "  ", "。", "！", "；", ";"};
        
        for (String sep : separators) {
            List<String> split = new ArrayList<>();
            for (String part : question.split(Pattern.quote(sep))) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) split.add(trimmed);
            }
            if (split.size() > 1) {
                parts = split;
                break;
            }
        }
        
        // 如果分隔符未拆开，尝试在意图连接词处拆解
        // 匹配模式如 "北京天气和杭州美食"、"天气查询与景点推荐"
        if (parts.size() <= 1) {
            // 先检测是否包含多个意图关键词（含连接词）
            String[] intentKeywords = {"天气", "美食", "旅游", "景点", "新闻", "计算", "图片", "汇率"};
            int intentCount = 0;
            for (String kw : intentKeywords) {
                if (question.contains(kw)) intentCount++;
            }
            // 含多个意图且中间有连接词 → 按连接词分割
            if (intentCount >= 2) {
                String[] connectWords = {" 和 ", " 以及 ", " 与 ", "和", "以及", "与"};
                for (String conn : connectWords) {
                    if (question.contains(conn)) {
                        String[] kwSplit = question.split(Pattern.quote(conn));
                        List<String> temp = new ArrayList<>();
                        for (String s : kwSplit) {
                            String t = s.trim();
                            if (!t.isEmpty()) temp.add(t);
                        }
                        if (temp.size() > 1) {
                            parts = temp;
                            break;
                        }
                    }
                }
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
            // 直接用 handleSingleIntent 路由和执行，移除重复的 selectBestAgent
            RoutingResult subResult = handleSingleIntent(subTask, context, userId);
            String detectedAgent = subResult.getAgentName() != null ? subResult.getAgentName() : "unknown";
            storeSseEvent(eventsKey, "routed", "🎯 正在处理: " + subTask, detectedAgent);
            subResults.add(new SubTaskResult(subTask, subResult.getResult(), detectedAgent));
            if (firstAgent == null) firstAgent = detectedAgent;

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
     * ⭐ 从完整 Prompt 中提取原始问题
     * <p>
     * Router 接收到的 question 可能包含【用户历史信息】【用户画像】【当前问题】等模板标记。
     * 提取【当前问题】后的文本作为缓存使用的原始问题，确保同一问题的 MD5 一致。
     * <p>
     * 格式示例：
     * 【用户历史信息】
     * - 历史查询: 3次
     *
     * 【当前问题】
     * 故宫几点关门
     * <p>
     * 提取后返回: "故宫几点关门"
     */
    private String extractRawQuestion(String question) {
        if (question == null || question.isBlank()) return question;
        // 查找【当前问题】标记
        int currentQuestionIdx = question.indexOf("【当前问题】");
        if (currentQuestionIdx >= 0) {
            String after = question.substring(currentQuestionIdx + "【当前问题】".length()).trim();
            // 去除可能的前缀（如换行、编号等），取纯文本
            return after.replaceAll("^[\\s\\n\\r]+", "").trim();
        }
        // 无标记直接返回完整文本
        return question.trim();
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
