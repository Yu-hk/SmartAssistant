/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.RouteRequest;
import com.example.smartassistant.router.model.RoutingResult;
import com.example.smartassistant.router.model.SubTask;
import com.example.smartassistant.router.model.SubTaskResult;
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
    private final ChatClient chatClient;
    private final StringRedisTemplate redisTemplate;
    private final RouterRagService ragService;
    private final SemanticRouteCacheService semanticCache;
    private final TaskPlannerService taskPlanner;
    private final ResultMerger resultMerger;

    @Value("${router.agent.rag.enabled:false}")
    private boolean ragEnabled;

    @Value("${router.context.history.max-messages:10}")
    private int maxHistoryMessages;

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
                         SemanticRouteCacheService semanticCache,
                         TaskPlannerService taskPlanner,
                         ResultMerger resultMerger) {
        this.agentCallerService = agentCallerService;
        this.chatClient = chatClientBuilder.build();
        this.redisTemplate = redisTemplate;
        this.ragService = ragService;
        this.semanticCache = semanticCache;
        this.taskPlanner = taskPlanner;
        this.resultMerger = resultMerger;
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
            } else {
                // ⭐ 多 Agent 协作（所有提问均走规划→执行→合并）
                // 简单问题 plan() 返回单个子任务，merge() 直接返回
                // 复杂问题自动分解为多个子任务并行执行
                log.info("[Router] 🤝 启动多 Agent 协作: question={}", truncate(enhancedQuestion, 80));
                result = executeCollaborative(enhancedQuestion, userId, request.getRequestId());
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
                    if (reply != null && !reply.isBlank() && !reply.startsWith("❌") && !reply.startsWith("⚠️")) {
                        semanticCache.saveReply(question, reply, agentName, intentTag, Boolean.TRUE.equals(result.getAdminOperation()));
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

    // ==================== 多 Agent 协作 ====================

    /**
     * 多 Agent 协作：任务分解 → 并行执行 → 结果合并。
     * <p>
     * 处理跨领域复杂问题，如"推荐北京景点和川菜馆"，
     * 分别发给 location_weather 和 food_recommendation 后合并。
     */
    private RoutingResult executeCollaborative(String question, Long userId, String requestId) {
        long start = System.currentTimeMillis();

        // Step 1: 任务分解
        List<SubTask> tasks = taskPlanner.plan(question);
        if (tasks.isEmpty()) {
            log.warn("[Collaborative] 任务分解为空，降级到内联 ChatClient 兜底");
            return inlineFallback(question);
        }
        log.info("[Collaborative] 任务分解: {} 个子任务", tasks.size());

        String eventsKey = requestId != null ? SSE_EVENTS_KEY_PREFIX + requestId : null;

        // Step 2: 并行执行子任务
        List<SubTaskResult> results = parallelExecute(tasks, userId, eventsKey);

        // Step 3: SSE — 汇总中
        storeSseEvent(eventsKey, "summarizing", "正在整合多源信息...", null);

        // Step 4: 合并结果
        String merged = resultMerger.merge(question, results);

        long elapsed = System.currentTimeMillis() - start;

        // Step 5: 终极兜底 — 所有 Agent 均失败或结果为空时走内联 ChatClient
        boolean allFailed = results.isEmpty() || results.stream().noneMatch(SubTaskResult::isSuccess);
        if (allFailed || merged == null || merged.isBlank()) {
            log.warn("[Collaborative] 所有子任务均失败，降级到内联 ChatClient 兜底");
            return inlineFallback(question);
        }

        log.info("[Collaborative] 协作完成: {} 个子任务, 耗时={}ms, 结果长度={}",
                results.size(), elapsed, merged.length());

        String firstAgent = results.stream()
                .map(SubTaskResult::getAgentName)
                .filter(Objects::nonNull)
                .findFirst().orElse("none");

        return RoutingResult.builder()
                .result(merged)
                .agentName(firstAgent)
                .confidence(0.8)
                .build();
    }

    /**
     * 带共享上下文的顺序执行。
     * <p>
     * 每个子任务执行前，将已完成的 Agent 结果（共享上下文）注入到描述中，
     * 使后续 Agent 能感知前面 Agent 的发现。
     * 例如 Food Agent 看到 Travel Agent 找到的景点后，可推荐附近的餐厅。
     */
    private List<SubTaskResult> parallelExecute(List<SubTask> tasks, Long userId, String eventsKey) {
        List<SubTaskResult> results = new ArrayList<>();
        StringBuilder sharedContext = new StringBuilder();

        for (SubTask task : tasks) {
            // 将已有共享上下文附加到子任务描述中
            String enrichedDesc = task.getDescription();
            if (!sharedContext.isEmpty()) {
                enrichedDesc = task.getDescription() + "\n\n[已知信息]\n" + sharedContext.toString().trim()
                        + "\n\n请结合以上已知信息回答，避免重复，侧重补充新内容。";
            }

            storeSseEvent(eventsKey, "routed",
                    "🎯 正在处理: " + task.getDescription(), task.getTargetAgent());

            try {
                String agentResult = agentCallerService.callAgent(
                        task.getTargetAgent(), enrichedDesc, userId);
                if (agentResult != null && !agentResult.isBlank()) {
                    // 将结果加入共享上下文，供后续 Agent 使用
                    sharedContext.append("【").append(task.getTargetAgent()).append("】")
                            .append(agentResult).append("\n\n");
                    results.add(new SubTaskResult(task.getId(), task.getDescription(),
                            task.getTargetAgent(), agentResult, true));
                    storeSseEvent(eventsKey, "response",
                            agentResult.substring(0, Math.min(200, agentResult.length())),
                            task.getTargetAgent());
                } else {
                    results.add(new SubTaskResult(task.getId(), task.getDescription(),
                            task.getTargetAgent(), "", false));
                }
            } catch (Exception e) {
                log.warn("[Collaborative] 子任务失败: task={}, error={}", task.getId(), e.getMessage());
                results.add(new SubTaskResult(task.getId(), task.getDescription(),
                        task.getTargetAgent(), "", false));
            }
        }
        return results;
    }

    /**
     * 内联 ChatClient 兜底（handleSingleIntent 的第三、四层），
     * 当所有 Agent 调用失败时的终极回应方案。
     */
    private RoutingResult inlineFallback(String question) {
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

        int idx = fallbackIndex.getAndUpdate(i -> (i + 1) % FALLBACK_MESSAGES.size());
        return RoutingResult.builder()
                .result(FALLBACK_MESSAGES.get(idx))
                .agentName("none")
                .confidence(0.0)
                .build();
    }

    private static final String SSE_EVENTS_KEY_PREFIX = "routing:sse:events:";
    private static final long SSE_EVENTS_TTL_SECONDS = 120;

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

    /*
      子任务结果
     */
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
