/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.model.tier.TieredModelRouter;
import com.example.smartassistant.common.model.tier.TierSelection;
import com.example.smartassistant.common.prompt.PromptManager;
import com.example.smartassistant.router.model.*;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import com.example.smartassistant.router.service.experience.ExperienceService;
import com.example.smartassistant.router.service.guardrail.EmotionCheckResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多 Agent 协作执行服务。
 * <p>
 * 从 RouterService 拆出，负责图分解 → 并行执行 → 结果合并 → 兜底的全流程。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-13
 */
@Service
public class RouteExecutionService {

    private static final Logger log = LoggerFactory.getLogger(RouteExecutionService.class);

    private static final String SSE_EVENTS_KEY_PREFIX = "routing:sse:events:";
    private static final long SSE_EVENTS_TTL_SECONDS = 120;
    private static final int MAX_SSE_EVENTS_PER_KEY = 5_000;

    private static final List<String> FALLBACK_MESSAGES = List.of(
            "😅 抱歉让你等了这么久，目前服务似乎遇到了一些临时问题。请稍后再试一下，或者联系技术支持看看。谢谢你的耐心！",
            "🙏 不好意思让你久等了，系统这会儿有点忙不过来，暂时没办法回应你的问题。过一会儿再找我试试吧！",
            "🤗 哎呀，好像出了点小岔子……你先别着急，我这边正在努力恢复中，等一小会儿再来找我聊聊好吗？",
            "😊 真抱歉，刚才没能帮上忙。系统可能在打盹儿，你先去喝杯水，待会儿再来找我试试看？"
    );

    private final AtomicInteger fallbackIndex = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Integer> sseEventCounts = new ConcurrentHashMap<>();

    private final AgentCallerService agentCallerService;
    private final TaskPlannerService taskPlanner;
    private final GraphExecutionService graphExecutionService;
    private final ResultMerger resultMerger;
    private final ExperienceService experienceService;
    private final RouteFinalizer routeFinalizer;
    private final PromptManager promptManager;
    private final ChatClient lightChatClient;
    private final StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private TieredModelRouter tieredModelRouter;

    @Autowired(required = false)
    private DegradationService degradationService;

    public RouteExecutionService(
            AgentCallerService agentCallerService,
            TaskPlannerService taskPlanner,
            GraphExecutionService graphExecutionService,
            ResultMerger resultMerger,
            ExperienceService experienceService,
            RouteFinalizer routeFinalizer,
            PromptManager promptManager,
            @Qualifier("lightChatModel") ChatModel lightModel,
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.agentCallerService = agentCallerService;
        this.taskPlanner = taskPlanner;
        this.graphExecutionService = graphExecutionService;
        this.resultMerger = resultMerger;
        this.experienceService = experienceService;
        this.routeFinalizer = routeFinalizer;
        this.promptManager = promptManager;
        this.lightChatClient = ChatClient.create(lightModel);
        this.redisTemplate = redisTemplate;
    }

    // ==================== 公共 API ====================

    /**
     * 多 Agent 协作：图分解 → 图执行 → 结果合并。
     */
    public RoutingResult executeCollaborative(String question, Long userId, String requestId,
                                               EmotionCheckResult emotion) {
        long start = System.currentTimeMillis();

        if (agentCallerService.getAvailableAgentCount() == 0) {
            log.warn("[Collaborative] 无可用 Agent，降级到内联 ChatClient 兜底");
            return inlineFallback(question, emotion);
        }

        DegradationService.DegradationLevel degLevel = (degradationService != null)
                ? degradationService.getDegradationLevel()
                : DegradationService.DegradationLevel.NORMAL;

        if (degLevel == DegradationService.DegradationLevel.HALF_OPEN) {
            log.info("[Collaborative] 🟡 半开探测: 放行一次请求验证恢复");
            RoutingResult probeResult = inlineFallback(question, emotion);
            boolean success = probeResult != null && probeResult.getResult() != null
                    && !probeResult.getResult().isBlank()
                    && !probeResult.getResult().startsWith("❌");
            if (degradationService != null) {
                degradationService.recordProbeResult(success);
            }
            return probeResult;
        }
        if (degLevel == DegradationService.DegradationLevel.HEAVY) {
            log.warn("[Collaborative] 🔴 重度降级，跳过所有 Agent 调用，回退到内联兜底");
            return inlineFallback(question, emotion);
        }
        if (degLevel == DegradationService.DegradationLevel.LIGHT) {
            log.warn("[Collaborative] 🟡 轻度降级，跳过复杂 DAG");
            String reply = agentCallerService.callAgent("general_agent", question, userId, requestId);
            if (reply == null || reply.isBlank() || reply.startsWith("❌")) {
                return inlineFallback(question, emotion);
            }
            long elapsed = System.currentTimeMillis() - start;
            log.info("[Collaborative] 降级单 Agent 完成: elapsed={}ms, replyLen={}", elapsed, reply.length());
            return routeFinalizer.applyEmotion(RoutingResult.builder()
                    .result(reply).agentName("general_agent").confidence(1.0).build(), emotion);
        }

        IntentGraph graph = taskPlanner.planToGraph(question);
        if (graph.getNodeCount() == 0) {
            log.warn("[Collaborative] 图分解为空，降级到内联 ChatClient 兜底");
            return inlineFallback(question, emotion);
        }

        String eventsKey = requestId != null ? SSE_EVENTS_KEY_PREFIX + requestId : null;
        List<SubTaskResult> results = graphExecutionService.executeWithResume(graph, userId, eventsKey, requestId);

        storeSseEvent(eventsKey, "summarizing", "正在整合多源信息...", null);

        String merged = resultMerger.merge(question, results);
        long elapsed = System.currentTimeMillis() - start;

        boolean allFailed = results.isEmpty() || results.stream().noneMatch(SubTaskResult::isSuccess);
        if (allFailed || merged == null || merged.isBlank()) {
            log.warn("[Collaborative] 所有子任务均失败，降级到内联 ChatClient 兜底");
            return inlineFallback(question, emotion);
        }

        log.info("[Collaborative] 协作完成: {} 个子任务, 耗时={}ms, 结果长度={}",
                results.size(), elapsed, merged.length());

        String firstAgent = results.stream()
                .map(SubTaskResult::getAgentName)
                .filter(Objects::nonNull)
                .findFirst().orElse("none");

        if (graph.getNodeCount() >= 2) {
            experienceService.extractReactExperience(question,
                    graph.getAllNodes().stream()
                            .map(n -> new SubTask(n.getId(), n.getDescription(), n.getTargetAgent(), n.getDependsOn()))
                            .collect(java.util.stream.Collectors.toList()));
        }

        return routeFinalizer.applyEmotion(RoutingResult.builder()
                .result(merged).agentName(firstAgent).confidence(0.8).build(), emotion);
    }

    /**
     * 调用 Agent → 构建 RoutingResult → finalizeRouting 后处理。
     */
    public RoutingResult callAgentAndFinalize(String agentName, String agentQuestion,
                                               double confidence, String intentTag,
                                               RouteRequest request, String rawQuestion,
                                               EmotionCheckResult emotion) {
        String agentReply = agentCallerService.callAgent(
                agentName, agentQuestion, request.getUserId(), request.getRequestId());
        if (agentReply == null || agentReply.isBlank()) {
            return null;
        }
        RoutingResult result = RoutingResult.builder()
                .result(agentReply)
                .agentName(agentName)
                .confidence(confidence)
                .intentTag(intentTag)
                .build();
        return routeFinalizer.finalizeRouting(result, request, rawQuestion, emotion);
    }

    // ==================== 内联兜底 ====================

    /**
     * 内联 Ollama 兜底：本地推理 → 预设文案轮换。
     */
    public RoutingResult inlineFallback(String question, EmotionCheckResult emotion) {
        String warmSystem = promptManager.inlineFallback();
        try {
            String localReply;
            if (tieredModelRouter != null) {
                Prompt prompt = new Prompt(List.of(
                        new SystemMessage(warmSystem),
                        new UserMessage(question)));
                TierSelection sel = tieredModelRouter.call(prompt, question, null);
                localReply = sel.response() != null && sel.response().getResult() != null
                        ? sel.response().getResult().getOutput().getText() : null;
            } else {
                localReply = lightChatClient.prompt()
                        .system(warmSystem)
                        .user(question)
                        .call()
                        .content();
            }
            if (localReply != null && !localReply.isBlank()) {
                return routeFinalizer.applyEmotion(RoutingResult.builder()
                        .result(localReply).agentName("builtin_fallback").confidence(0.2).build(), emotion);
            }
        } catch (Exception e) {
            log.warn("[Exec] 本地推理兜底失败: {}", e.getMessage());
        }

        int idx = fallbackIndex.getAndUpdate(i -> (i + 1) % FALLBACK_MESSAGES.size());
        return routeFinalizer.applyEmotion(RoutingResult.builder()
                .result(FALLBACK_MESSAGES.get(idx)).agentName("none").confidence(0.0).build(), emotion);
    }

    // ==================== SSE 事件 ====================

    private void storeSseEvent(String eventsKey, String type, String content, String agent) {
        if (eventsKey == null || redisTemplate == null) return;
        int count = sseEventCounts.merge(eventsKey, 1, Integer::sum);
        if (count > MAX_SSE_EVENTS_PER_KEY) {
            if (count == MAX_SSE_EVENTS_PER_KEY + 1) {
                log.warn("[Exec] SSE 事件数已达上限 ({}), 停止缓存: key={}", MAX_SSE_EVENTS_PER_KEY, eventsKey);
            }
            return;
        }
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
            log.warn("[Exec] 存储 SSE 事件失败: {}", e.getMessage());
            sseEventCounts.computeIfPresent(eventsKey, (k, v) -> v > 0 ? v - 1 : 0);
        }
    }

    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
