/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.HandoffCommand;
import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.IntentGraph.IntentNode;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.model.SubTaskResult.ErrorType;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * ⭐ 基于意图图（DAG）的多 Agent 执行引擎。
 * <p>
 * 根据 {@link IntentGraph} 的拓扑结构，按依赖关系分层执行子任务：
 * <ol>
 *   <li><b>无依赖节点</b>（根节点）：使用 {@link #parallelExecutor} 并行执行</li>
 *   <li><b>有依赖节点</b>：等待所有前序节点完成后，收集共享上下文再执行</li>
 *   <li><b>死锁保护</b>：连续两轮无新完成节点时终止循环</li>
 * </ol>
 * </p>
 * <p>
 * 与旧版顺序执行相比的优势：
 * <ul>
 *   <li>"查北京天气"和"查北京景点"互不依赖 → 同时执行，耗时=Max(ta, tb)</li>
 *   <li>"查景点→推荐景点附近餐厅"有依赖 → 顺序执行，但共享上下文自动传递</li>
 * </ul>
 * </p>
 */
@Service
public class GraphExecutionService {

    private static final Logger log = LoggerFactory.getLogger(GraphExecutionService.class);

    private final AgentCallerService agentCallerService;
    private final Executor parallelExecutor;
    private final ReflectionService reflectionService;
    private final TaskPlannerService taskPlannerService;

    /** ⭐ 异步 Agent 事件总线（可选，null 时降级为同步 Handoff） */
    private final com.example.smartassistant.common.gateway.AgentEventBus agentEventBus;

    /** ⭐ 是否启用异步 Handoff（默认 false） */
    @Value("${router.graph.async-handoff-enabled:false}")
    private boolean asyncHandoffEnabled;

    public GraphExecutionService(AgentCallerService agentCallerService,
                                 @Qualifier("routerParallelAgentExecutor") Executor parallelExecutor,
                                 ReflectionService reflectionService,
                                 TaskPlannerService taskPlannerService,
                                 @Autowired(required = false) com.example.smartassistant.common.gateway.AgentEventBus agentEventBus) {
        this.agentCallerService = agentCallerService;
        this.parallelExecutor = parallelExecutor;
        this.reflectionService = reflectionService;
        this.taskPlannerService = taskPlannerService;
        this.agentEventBus = agentEventBus;
    }

    /**
     * 执行意图图，返回所有子任务的结果。
     * <p>
     * 执行策略：
     * <ol>
     *   <li>取当前可执行节点（所有依赖已完成的节点）</li>
     *   <li>用 {@link CompletableFuture#supplyAsync} 并行执行这批节点</li>
     *   <li>{@link CompletableFuture#allOf} 等待同层全部完成</li>
     *   <li>重复以上步骤直到所有节点完成</li>
     *   <li>死锁检测：连续 2 轮无新完成节点时中断</li>
     * </ol>
     * </p>
     *
     * @param graph     意图图
     * @param userId    用户 ID
     * @param eventsKey SSE 事件 key（可为 null）
     * @param requestId 请求 ID（日志追踪）
     * @return 所有子任务结果列表
     */
    public List<SubTaskResult> execute(IntentGraph graph, Long userId, String eventsKey, String requestId) {
        // 已完成节点 id → SubTaskResult
        final ConcurrentHashMap<String, SubTaskResult> completedMap = new ConcurrentHashMap<>();
        // 所有结果（有序）
        final List<SubTaskResult> allResults = new CopyOnWriteArrayList<>();

        int totalNodes = graph.getNodeCount();
        log.info("[GraphExecutor] 开始执行意图图: {} 个节点, hasDeps={}, maxParallelism={}",
                totalNodes, graph.hasDependency(), graph.getMaxParallelism());

        int round = 0;
        int previousCompleted = 0;
        int staleRounds = 0;
        final int MAX_STALE_ROUNDS = 2; // 连续无进展轮数上限

        while (!graph.isCompleted(completedMap.keySet())) {
            round++;

            // 死锁检测
            if (graph.hasDeadlock(completedMap.keySet())) {
                log.error("[GraphExecutor] ⚠️ 检测到死锁: round={}, completed={}/{}, 终止执行",
                        round, completedMap.size(), totalNodes);
                break;
            }

            // 获取当前可执行节点
            List<IntentNode> executableNodes = graph.getExecutableNodes(completedMap.keySet());
            if (executableNodes.isEmpty()) {
                log.warn("[GraphExecutor] 无可用执行节点: round={}, completed={}/{}",
                        round, completedMap.size(), totalNodes);
                break;
            }

            log.info("[GraphExecutor] 第{}轮: {} 个可执行节点", round, executableNodes.size());

            // 并行执行这一批节点
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (IntentNode node : executableNodes) {
                CompletableFuture<Void> future = CompletableFuture
                        .supplyAsync(() -> executeNode(node, graph, completedMap, userId, eventsKey, requestId), parallelExecutor)
                        .thenAccept(result -> {
                            if (result != null) {
                                completedMap.put(node.getId(), result);
                                allResults.add(result);
                            }
                        })
                        .exceptionally(ex -> {
                            log.error("[GraphExecutor] 节点执行异常: node={}, error={}", node.getId(), ex.getMessage());
                            ErrorType errorType = classifyException(ex);
                            completedMap.put(node.getId(), new SubTaskResult(
                                    node.getId(), node.getDescription(),
                                    node.getTargetAgent(), "", false, errorType));
                            return null;
                        });
                futures.add(future);
            }

            // 等待本轮所有节点完成
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.warn("[GraphExecutor] 第{}轮超时(60s): completed={}/{}", round, completedMap.size(), totalNodes);
                // 超时的节点标记为 RETRYABLE_FAILED（瞬时错误）
                for (IntentNode node : executableNodes) {
                    completedMap.putIfAbsent(node.getId(), new SubTaskResult(
                            node.getId(), node.getDescription(),
                            node.getTargetAgent(), "", false,
                            SubTaskResult.ErrorType.RETRYABLE_FAILED));
                }
            } catch (Exception e) {
                log.warn("[GraphExecutor] 第{}轮异常: {}", round, e.getMessage());
                Thread.currentThread().interrupt();
                break;
            }

            // 无进展检测
            int currentCompleted = completedMap.size();
            if (currentCompleted == previousCompleted) {
                staleRounds++;
                if (staleRounds >= MAX_STALE_ROUNDS) {
                    log.warn("[GraphExecutor] 连续 {} 轮无进展，终止执行", MAX_STALE_ROUNDS);
                    break;
                }
            } else {
                staleRounds = 0;
            }
            previousCompleted = currentCompleted;

            // ⭐ 重规划检测：本轮执行完毕后，检查是否有 NEED_REPLAN 节点
            int replanCount = triggerReplanIfNeeded(graph, completedMap, allResults,
                    userId, eventsKey, requestId);
            if (replanCount > 0) {
                // 恢复计数，因为新增了节点需要继续执行
                staleRounds = 0;
                previousCompleted = completedMap.size();
            }
        }

        log.info("[GraphExecutor] 执行完成: completed={}/{}, rounds={}", completedMap.size(), totalNodes, round);
        return allResults;
    }

    /**
     * 执行意图图，支持 Handoff 显式交接。
     *
     * <p>在 {@link #execute(IntentGraph, Long, String, String)} 的标准 DAG 并行执行基础上，
     * 增加 Handoff 检测：每轮执行完毕后，检查是否有子任务返回了 {@link HandoffCommand}。
     * 如有，动态创建新节点并加入待执行队列，实现「Agent A → 命令交 → Agent B」的显式移交。</p>
     *
     * <p>Handoff 场景示例：</p>
     * <ul>
     *   <li>General Agent 检测到复杂订单查询 → {@code HandoffCommand(order_agent, ...)}</li>
     *   <li>Order Agent 发现商品相关问题 → {@code HandoffCommand(product_agent, ...)}</li>
     * </ul>
     *
     * @param graph     意图图（初始 DAG）
     * @param userId    用户 ID
     * @param eventsKey SSE 事件 key（可为 null）
     * @param requestId 请求 ID
     * @return 所有子任务结果列表（含 Handoff 动态追加的节点结果）
     */
    public List<SubTaskResult> executeWithHandoff(IntentGraph graph, Long userId,
                                                   String eventsKey, String requestId) {
        // 执行标准 DAG（同 execute 方法逻辑）
        List<SubTaskResult> results = execute(graph, userId, eventsKey, requestId);

        // ⭐ 检测是否有 Handoff 请求
        List<SubTaskResult> handoffResults = processHandoffs(results, graph, userId, eventsKey, requestId);
        while (!handoffResults.isEmpty()) {
            results.addAll(handoffResults);
            handoffResults = processHandoffs(results, graph, userId, eventsKey, requestId);
        }

        return results;
    }

    /**
     * 处理一轮 Handoff 交接。
     * <p>
     * 根据 {@link #asyncHandoffEnabled} 配置，选择同步 HTTP 调用
     * 或异步 Redis List 事件总线模式。
     * </p>
     */
    private List<SubTaskResult> processHandoffs(List<SubTaskResult> results, IntentGraph graph,
                                                 Long userId, String eventsKey, String requestId) {
        List<SubTaskResult> newResults = new ArrayList<>();

        for (SubTaskResult result : results) {
            if (result == null || !result.hasHandoff()) continue;

            HandoffCommand cmd = result.getHandoffCommand();

            if (cmd.handoffType() == HandoffCommand.HandoffType.COMPLETE) {
                log.info("[GraphExecutor] Handoff COMPLETE: 源自 {}", result.getAgentName());
                continue;
            }
            if (cmd.handoffType() == HandoffCommand.HandoffType.FAILED) {
                log.warn("[GraphExecutor] Handoff FAILED: agent={}, 尝试兜底", result.getAgentName());
                continue;
            }

            log.info("[GraphExecutor] 🔀 Handoff: {} → {}, question={}",
                    result.getAgentName(), cmd.targetAgent(),
                    truncate(cmd.question(), 100));

            if (asyncHandoffEnabled && agentEventBus != null) {
                // ⭐ 异步模式：发布事件到 Redis List，不等待返回
                publishAsyncHandoff(cmd);
                // 异步 Handoff 不产生即时结果
            } else {
                // 同步模式：HTTP 调用等待返回（旧逻辑）
                SubTaskResult handoffResult = executeHandoffNode(cmd, userId, eventsKey, requestId);
                if (handoffResult != null) {
                    newResults.add(handoffResult);
                }
            }
        }

        return newResults;
    }

    /**
     * ⭐ 异步 Handoff：将 Handoff 命令发布到 Redis List 事件总线。
     * <p>
     * Router 不等待结果，Agent 通过 BLPOP 异步消费。
     * 适用于无需立即返回结果的 Agent 间协作。
     * </p>
     */
    private void publishAsyncHandoff(HandoffCommand cmd) {
        if (agentEventBus == null) return;
        agentEventBus.publishEvent(cmd.targetAgent(), "HANDOFF", Map.of(
                "question", cmd.question(),
                "contextPayload", cmd.contextPayload() != null ? cmd.contextPayload() : "",
                "handoffType", cmd.handoffType().name()
        ));
        log.info("[GraphExecutor] 📨 异步 Handoff 已发布: → {}", cmd.targetAgent());
    }

    /**
     * 执行 Handoff 命令指定的目标 Agent。
     */
    private SubTaskResult executeHandoffNode(HandoffCommand cmd, Long userId,
                                              String eventsKey, String requestId) {
        String handoffTaskId = "handoff_" + cmd.targetAgent() + "_" + System.currentTimeMillis();
        try {
            String enrichedQuestion = cmd.question();
            if (cmd.contextPayload() != null && !cmd.contextPayload().isBlank()) {
                enrichedQuestion = cmd.question()
                        + "\n\n[Handoff 上下文]\n" + cmd.contextPayload()
                        + "\n\n请结合以上上下文回答，避免重复。";
            }

            var agentResult = agentCallerService.callAgentAndExtractTitles(
                    cmd.targetAgent(), enrichedQuestion, userId, requestId);
            String resultText = agentResult.getResponse();

            if (resultText != null && !resultText.isBlank()) {
                log.info("[GraphExecutor] Handoff 成功: {} → {}, resultLen={}",
                        cmd.targetAgent(), cmd.handoffType(), resultText.length());
                return new SubTaskResult(handoffTaskId,
                        "Handoff: " + cmd.targetAgent(), cmd.targetAgent(),
                        resultText, true,
                        agentResult.getRealTitles(), agentResult.getTagsByTitle());
            }
        } catch (Exception e) {
            log.warn("[GraphExecutor] Handoff 执行异常: {} → {}, error={}",
                    cmd.targetAgent(), cmd.handoffType(), e.getMessage());
        }

        return new SubTaskResult(handoffTaskId,
                "Handoff: " + cmd.targetAgent(), cmd.targetAgent(), "", false);
    }

    /** 截断长文本（日志用） */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 异常分类——根据异常类型映射到 {@link SubTaskResult.ErrorType}。
     * <p>
     * 分类规则：
     * <ul>
     *   <li>{@link java.util.concurrent.TimeoutException} → RETRYABLE_FAILED</li>
     *   <li>{@link java.net.SocketTimeoutException} → RETRYABLE_FAILED</li>
     *   <li>{@link java.io.IOException} 含 "timeout"/"connect"/"refused" → RETRYABLE_FAILED</li>
     *   <li>其他异常 → FATAL_FAILED（默认不可重试）</li>
     * </ul>
     * </p>
     */
    static SubTaskResult.ErrorType classifyException(Throwable ex) {
        if (ex == null) return SubTaskResult.ErrorType.FATAL_FAILED;

        // 超时类异常 → 可重试
        if (ex instanceof java.util.concurrent.TimeoutException) {
            return SubTaskResult.ErrorType.RETRYABLE_FAILED;
        }
        if (ex instanceof java.net.SocketTimeoutException) {
            return SubTaskResult.ErrorType.RETRYABLE_FAILED;
        }

        // IO 异常中含超时/连接关键词 → 可重试
        if (ex instanceof java.io.IOException) {
            String msg = ex.getMessage();
            if (msg != null && (msg.contains("timeout") || msg.contains("connect")
                    || msg.contains("refused") || msg.contains("reset"))) {
                return SubTaskResult.ErrorType.RETRYABLE_FAILED;
            }
        }

        // 检查 cause 链
        Throwable cause = ex.getCause();
        if (cause != null && cause != ex) {
            return classifyException(cause); // 递归检查 cause
        }

        return SubTaskResult.ErrorType.FATAL_FAILED;
    }

    /**
     * 执行单个图节点（含指数退避重试 + 验收标准检查）。
     * <p>
     * 执行策略：
     * <ol>
     *   <li>从 {@code completedMap} 中按节点的依赖关系组装共享上下文</li>
     *   <li>调用 Agent 执行，失败时根据 {@link ErrorType} 决定是否重试</li>
     *   <li>{@link ErrorType#RETRYABLE_FAILED}：指数退避重试（最多 3 次）</li>
     *   <li>{@link ErrorType#FATAL_FAILED}：立即返回</li>
     *   <li>成功后调用 {@link ReflectionService#checkCriteria} 做验收检查</li>
     * </ol>
     * </p>
     */
    private SubTaskResult executeNode(IntentNode node, IntentGraph graph,
                                       ConcurrentHashMap<String, SubTaskResult> completedMap,
                                       Long userId, String eventsKey, String requestId) {
        log.debug("[GraphExecutor] 执行节点: {}|{}|{}", node.getId(), node.getDescription(), node.getTargetAgent());

        // 从依赖节点构建共享上下文
        String sharedContext = buildSharedContext(node, completedMap);

        // 将共享上下文注入描述
        String enrichedDesc = node.getDescription();
        if (!sharedContext.isEmpty()) {
            enrichedDesc = enrichedDesc + "\n\n[已知信息]\n" + sharedContext.trim()
                    + "\n\n请结合以上已知信息回答，避免重复，侧重补充新内容。";
        }

        int maxRetries = 3;
        long baseDelayMs = 1000;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                var agentResult = agentCallerService.callAgentAndExtractTitles(
                        node.getTargetAgent(), enrichedDesc, userId, requestId);
                String resultText = agentResult.getResponse();

                if (resultText != null && !resultText.isBlank()) {
                    // ⭐ 验收标准检查
                    ErrorType criteriaResult = reflectionService.checkCriteria(
                            resultText, node.getSuccessCriteria());
                    if (criteriaResult == ErrorType.NEED_REPLAN) {
                        log.warn("[GraphExecutor] 验收不通过: node={}|{}, criteria={}",
                                node.getId(), node.getTargetAgent(), node.getSuccessCriteria());
                        return new SubTaskResult(node.getId(), node.getDescription(),
                                node.getTargetAgent(), resultText, false,
                                ErrorType.NEED_REPLAN);
                    }
                    log.info("[GraphExecutor] 节点成功: {}|{}, resultLen={}",
                            node.getId(), node.getTargetAgent(), resultText.length());
                    return new SubTaskResult(node.getId(), node.getDescription(),
                            node.getTargetAgent(), resultText, true,
                            agentResult.getRealTitles(), agentResult.getTagsByTitle());
                }

                // 结果为空 → 可重试
                if (attempt < maxRetries) {
                    long delay = backoffDelay(baseDelayMs, attempt);
                    log.warn("[GraphExecutor] 节点返回空，重试: {}|{}, attempt={}/{}, delay={}ms",
                            node.getId(), node.getTargetAgent(), attempt + 1, maxRetries, delay);
                    backoffSleep(delay);
                    continue;
                }
                log.warn("[GraphExecutor] 节点返回空（已达最大重试）: {}|{}", node.getId(), node.getTargetAgent());
                return new SubTaskResult(node.getId(), node.getDescription(),
                        node.getTargetAgent(), "", false, ErrorType.FATAL_FAILED);

            } catch (Exception e) {
                ErrorType errorType = classifyException(e);

                if (errorType == ErrorType.RETRYABLE_FAILED && attempt < maxRetries) {
                    long delay = backoffDelay(baseDelayMs, attempt);
                    log.warn("[GraphExecutor] 节点异常(可重试): {}|{}, error={}, attempt={}/{}, delay={}ms",
                            node.getId(), node.getTargetAgent(), e.getMessage(),
                            attempt + 1, maxRetries, delay);
                    backoffSleep(delay);
                    continue;
                }

                log.warn("[GraphExecutor] 节点异常(不可重试): {}|{}, error={}, type={}",
                        node.getId(), node.getTargetAgent(), e.getMessage(), errorType);
                return new SubTaskResult(node.getId(), node.getDescription(),
                        node.getTargetAgent(), "", false, errorType);
            }
        }

        return new SubTaskResult(node.getId(), node.getDescription(),
                node.getTargetAgent(), "", false, ErrorType.FATAL_FAILED);
    }

    // ========================================================================
    // 辅助方法：重试 & 重规划
    // ========================================================================

    /** 指数退避延迟计算：1s → 2s → 4s */
    private static long backoffDelay(long baseMs, int attempt) {
        return baseMs * (1L << Math.min(attempt, 10));
    }

    /** 带中断保护的 sleep */
    private static void backoffSleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 检查本轮结果中是否有 NEED_REPLAN 节点，触发重规划。
     *
     * @return 触发重规划的节点数
     */
    private int triggerReplanIfNeeded(IntentGraph graph,
                                       ConcurrentHashMap<String, SubTaskResult> completedMap,
                                       List<SubTaskResult> allResults,
                                       Long userId, String eventsKey, String requestId) {
        // 找出 NEED_REPLAN 的节点（本轮刚完成的）
        List<SubTaskResult> needReplan = allResults.stream()
                .filter(r -> r.needsReplan() && completedMap.containsKey(r.getTaskId()))
                .collect(Collectors.toList());

        if (needReplan.isEmpty()) return 0;

        int count = 0;
        for (SubTaskResult failedResult : needReplan) {
            log.info("[GraphExecutor] 🔄 触发重规划: node={}, agent={}, criteria={}",
                    failedResult.getTaskId(), failedResult.getAgentName(),
                    truncate(failedResult.getDescription(), 80));

            List<IntentGraph.IntentNode> newNodes = replanFailedNode(
                    graph, failedResult, completedMap, allResults);

            if (newNodes != null && !newNodes.isEmpty()) {
                // 从已完成中移除旧节点（它失败了，需要新节点替代）
                completedMap.remove(failedResult.getTaskId());

                // 追加新节点到图
                graph.addNodes(newNodes);
                log.info("[GraphExecutor] 重规划完成: 新增 {} 个节点 (原node={})",
                        newNodes.size(), failedResult.getTaskId());
                count++;
            } else {
                log.warn("[GraphExecutor] 重规划失败，保留原结果: node={}", failedResult.getTaskId());
            }
        }
        return count;
    }

    /**
     * 对失败节点进行重规划。
     * <p>
     * 以原始问题 + 已完成成功的节点结果 + 当前失败节点上下文为输入，
     * 调用 TaskPlannerService 生成新的子任务图，替代原失败节点。
     * </p>
     */
    private List<IntentGraph.IntentNode> replanFailedNode(IntentGraph graph,
                                                           SubTaskResult failedResult,
                                                           Map<String, SubTaskResult> completedMap,
                                                           List<SubTaskResult> allResults) {
        // 收集已成功的上下文
        StringBuilder completedContext = new StringBuilder();
        for (SubTaskResult r : allResults) {
            if (r.isSuccess() && r.getResult() != null && !r.getResult().isBlank()
                    && !r.getTaskId().equals(failedResult.getTaskId())) {
                completedContext.append("【").append(r.getAgentName())
                        .append("】").append(truncate(r.getResult(), 300)).append("\n");
            }
        }

        String replanPrompt = String.format("""
                原始问题：%s

                已完成任务的结果：
                %s

                当前失败的任务【%s】：%s
                验收标准：%s
                失败原因：Agent 输出不满足验收标准。

                请为失败的任务重新规划子任务。考虑：
                1. 是否可以拆分任务使之更简单？
                2. 是否应该换一个 Agent 重试？
                3. 是否需要调整任务描述以引导 Agent 更精准地回答？

                输出格式与标准规划相同：子任务ID|描述|助理名|依赖ID列表(逗号分隔)|验收标准
                """,
                graph.getQuestion(),
                completedContext.length() > 0 ? completedContext.toString() : "（无）",
                failedResult.getTaskId(), failedResult.getDescription(),
                failedResult.getDescription() != null ? failedResult.getDescription() : "");

        try {
            IntentGraph replanGraph = taskPlannerService.replan(replanPrompt);
            if (replanGraph != null && replanGraph.getNodeCount() > 0) {
                return new ArrayList<>(replanGraph.getAllNodes());
            }
        } catch (Exception e) {
            log.warn("[GraphExecutor] 重规划调用异常: node={}, error={}",
                    failedResult.getTaskId(), e.getMessage());
        }

        return List.of();
    }

    // ========================================================================
    // 辅助方法：上下文构建 & 截断
    // ========================================================================

    /**
     * 从已完成节点中，按当前节点的依赖关系构建共享上下文文本。
     * <p>
     * 例如当前节点 t3 依赖 t1 和 t2：
     * <pre>
     * 【t1】故宫、天坛...
     * 【t2】北京天气 20°C...
     * </pre>
     * </p>
     */
    private String buildSharedContext(IntentNode node,
                                       ConcurrentHashMap<String, SubTaskResult> completedMap) {
        List<String> deps = node.getDependsOn();
        if (deps == null || deps.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (String depId : deps) {
            SubTaskResult depResult = completedMap.get(depId);
            if (depResult != null && depResult.isSuccess() && depResult.getResult() != null) {
                sb.append("【").append(depResult.getAgentName() != null ? depResult.getAgentName() : depId)
                        .append("】").append(depResult.getResult()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }
}
