/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.core;

import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.IntentGraph.IntentNode;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.service.agent.AgentCallerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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

    public GraphExecutionService(AgentCallerService agentCallerService,
                                 @Qualifier("routerParallelAgentExecutor") Executor parallelExecutor) {
        this.agentCallerService = agentCallerService;
        this.parallelExecutor = parallelExecutor;
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
                            completedMap.put(node.getId(), new SubTaskResult(
                                    node.getId(), node.getDescription(),
                                    node.getTargetAgent(), "", false));
                            return null;
                        });
                futures.add(future);
            }

            // 等待本轮所有节点完成
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.warn("[GraphExecutor] 第{}轮超时(60s): completed={}/{}", round, completedMap.size(), totalNodes);
                // 超时的节点标记为失败
                for (IntentNode node : executableNodes) {
                    completedMap.putIfAbsent(node.getId(), new SubTaskResult(
                            node.getId(), node.getDescription(),
                            node.getTargetAgent(), "", false));
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
        }

        log.info("[GraphExecutor] 执行完成: completed={}/{}, rounds={}", completedMap.size(), totalNodes, round);
        return allResults;
    }

    /**
     * 执行单个图节点。
     * <p>
     * 从 {@code completedMap} 中按节点的依赖关系组装共享上下文，
     * 注入到子任务描述中，然后调用 Agent 执行。
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

        try {
            var agentResult = agentCallerService.callAgentAndExtractTitles(
                    node.getTargetAgent(), enrichedDesc, userId, requestId);
            String resultText = agentResult.getResponse();
            if (resultText != null && !resultText.isBlank()) {
                log.info("[GraphExecutor] 节点成功: {}|{}, resultLen={}", node.getId(), node.getTargetAgent(), resultText.length());
                return new SubTaskResult(node.getId(), node.getDescription(),
                        node.getTargetAgent(), resultText, true,
                        agentResult.getRealTitles(), agentResult.getTagsByTitle());
            } else {
                log.warn("[GraphExecutor] 节点返回空: {}|{}", node.getId(), node.getTargetAgent());
                return new SubTaskResult(node.getId(), node.getDescription(),
                        node.getTargetAgent(), "", false);
            }
        } catch (Exception e) {
            log.warn("[GraphExecutor] 节点异常: {}|{}, error={}", node.getId(), node.getTargetAgent(), e.getMessage());
            return new SubTaskResult(node.getId(), node.getDescription(),
                    node.getTargetAgent(), "", false);
        }
    }

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
