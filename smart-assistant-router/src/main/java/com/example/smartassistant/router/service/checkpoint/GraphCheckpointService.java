/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.checkpoint;

import com.example.smartassistant.router.model.IntentGraph;
import com.example.smartassistant.router.model.SubTaskResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Graph 执行检查点服务 — 将 DAG 执行状态持久化到 Redis。
 * <p>
 * 对应 LangGraph 中 Checkpoint 的核心能力：持久化 State、支持断点恢复。
 * </p>
 *
 * <p>保存的数据：</p>
 * <ul>
 *   <li>Graph 结构（question + 所有节点）</li>
 *   <li>已完成节点的结果（completedMap）</li>
 *   <li>节点级熔断计数（breakerFailureCounts）</li>
 *   <li>执行进度（round / staleRounds / previousCompleted）</li>
 *   <li>上下文参数（userId / eventsKey / requestId）</li>
 * </ul>
 *
 * <p>Redis Key 格式：{@code a2a:graph:checkpoint:{executionId}}</p>
 * <p>默认 TTL：30 分钟（执行超时后自动清理）</p>
 *
 * @author Yu-hk
 * @since 2026-07-07
 */
@Service
public class GraphCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(GraphCheckpointService.class);

    /** Redis Key 前缀 */
    private static final String CHECKPOINT_KEY_PREFIX = "a2a:graph:checkpoint:";

    /** 默认 TTL（30 分钟） */
    private static final long DEFAULT_TTL_SECONDS = 1800;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public GraphCheckpointService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    // ==================== 数据模型 ====================

    /**
     * Checkpoint 快照 — 保存一次 Graph 执行的完整状态。
     */
    public static class GraphExecutionSnapshot {
        /** 请求 ID（作为 checkpoint key） */
        private String executionId;
        /** Graph 序列化数据 */
        private GraphData graphData;
        /** 已完成节点 id → 结果 JSON */
        private Map<String, SubTaskResultData> completedMap;
        /** 熔断计数器 */
        private Map<String, Integer> breakerFailureCounts;
        /** 当前执行轮次 */
        private int round;
        /** 连续无进展轮数 */
        private int staleRounds;
        /** 上一轮完成节点数 */
        private int previousCompleted;
        /** 上下文参数 */
        private Long userId;
        private String eventsKey;
        private String requestId;

        public GraphExecutionSnapshot() {}

        // Getters and Setters
        public String getExecutionId() { return executionId; }
        public void setExecutionId(String executionId) { this.executionId = executionId; }
        public GraphData getGraphData() { return graphData; }
        public void setGraphData(GraphData graphData) { this.graphData = graphData; }
        public Map<String, SubTaskResultData> getCompletedMap() { return completedMap; }
        public void setCompletedMap(Map<String, SubTaskResultData> completedMap) { this.completedMap = completedMap; }
        public Map<String, Integer> getBreakerFailureCounts() { return breakerFailureCounts; }
        public void setBreakerFailureCounts(Map<String, Integer> breakerFailureCounts) { this.breakerFailureCounts = breakerFailureCounts; }
        public int getRound() { return round; }
        public void setRound(int round) { this.round = round; }
        public int getStaleRounds() { return staleRounds; }
        public void setStaleRounds(int staleRounds) { this.staleRounds = staleRounds; }
        public int getPreviousCompleted() { return previousCompleted; }
        public void setPreviousCompleted(int previousCompleted) { this.previousCompleted = previousCompleted; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public String getEventsKey() { return eventsKey; }
        public void setEventsKey(String eventsKey) { this.eventsKey = eventsKey; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
    }

    /** Graph 序列化数据（不含执行状态） */
    public static class GraphData {
        private String question;
        private List<NodeData> nodes;

        public GraphData() {}

        public GraphData(String question, List<NodeData> nodes) {
            this.question = question;
            this.nodes = nodes;
        }

        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public List<NodeData> getNodes() { return nodes; }
        public void setNodes(List<NodeData> nodes) { this.nodes = nodes; }
    }

    /** 节点序列化数据 */
    public static class NodeData {
        private String id;
        private String description;
        private String targetAgent;
        private List<String> dependsOn;
        private String successCriteria;
        /** 条件依赖 */
        private List<ConditionalDepData> conditionalDeps;
        /** 是否需要人工审批 */
        private boolean humanApprovalRequired;

        public NodeData() {}

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getTargetAgent() { return targetAgent; }
        public void setTargetAgent(String targetAgent) { this.targetAgent = targetAgent; }
        public List<String> getDependsOn() { return dependsOn; }
        public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn; }
        public String getSuccessCriteria() { return successCriteria; }
        public void setSuccessCriteria(String successCriteria) { this.successCriteria = successCriteria; }
        public List<ConditionalDepData> getConditionalDeps() { return conditionalDeps; }
        public void setConditionalDeps(List<ConditionalDepData> conditionalDeps) { this.conditionalDeps = conditionalDeps; }
        public boolean isHumanApprovalRequired() { return humanApprovalRequired; }
        public void setHumanApprovalRequired(boolean humanApprovalRequired) { this.humanApprovalRequired = humanApprovalRequired; }
    }

    /** 条件依赖序列化数据 */
    public static class ConditionalDepData {
        private String sourceNodeId;
        private String conditionType;
        private String conditionValue;

        public ConditionalDepData() {}

        public String getSourceNodeId() { return sourceNodeId; }
        public void setSourceNodeId(String sourceNodeId) { this.sourceNodeId = sourceNodeId; }
        public String getConditionType() { return conditionType; }
        public void setConditionType(String conditionType) { this.conditionType = conditionType; }
        public String getConditionValue() { return conditionValue; }
        public void setConditionValue(String conditionValue) { this.conditionValue = conditionValue; }
    }

    /** SubTaskResult 序列化数据 */
    public static class SubTaskResultData {
        private String taskId;
        private String description;
        private String agentName;
        private String result;
        private boolean success;
        private String errorType;

        public SubTaskResultData() {}

        public SubTaskResultData(SubTaskResult r) {
            this.taskId = r.getTaskId();
            this.description = r.getDescription();
            this.agentName = r.getAgentName();
            this.result = r.getResult();
            this.success = r.isSuccess();
            this.errorType = r.getErrorType() != null ? r.getErrorType().name() : "NONE";
        }

        // Getters
        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getAgentName() { return agentName; }
        public void setAgentName(String agentName) { this.agentName = agentName; }
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getErrorType() { return errorType; }
        public void setErrorType(String errorType) { this.errorType = errorType; }
    }

    // ==================== 核心方法 ====================

    /**
     * 保存 Graph 执行快照到 Redis。
     *
     * @param executionId         执行 ID（通常使用 requestId）
     * @param graph               意图图
     * @param completedMap        已完成节点映射
     * @param breakerFailureCounts 熔断计数器
     * @param round               执行轮次
     * @param staleRounds         无进展轮数
     * @param previousCompleted   上一轮完成数
     * @param userId              用户 ID
     * @param eventsKey           SSE 事件 key
     * @param requestId           请求 ID
     */
    public void saveCheckpoint(String executionId, IntentGraph graph,
                                ConcurrentHashMap<String, SubTaskResult> completedMap,
                                ConcurrentHashMap<String, Integer> breakerFailureCounts,
                                int round, int staleRounds, int previousCompleted,
                                Long userId, String eventsKey, String requestId) {
        if (executionId == null || executionId.isBlank()) return;

        try {
            GraphExecutionSnapshot snapshot = buildSnapshot(executionId, graph,
                    completedMap, breakerFailureCounts, round, staleRounds,
                    previousCompleted, userId, eventsKey, requestId);

            String json = objectMapper.writeValueAsString(snapshot);
            redisTemplate.opsForValue().set(
                    checkpointKey(executionId), json, DEFAULT_TTL_SECONDS, TimeUnit.SECONDS);

            log.debug("[Checkpoint] 已保存: executionId={}, round={}, completed={}/{}",
                    executionId, round, completedMap.size(), graph.getNodeCount());
        } catch (Exception e) {
            log.warn("[Checkpoint] 保存失败: executionId={}, error={}", executionId, e.getMessage());
        }
    }

    /**
     * 恢复 Graph 执行快照（如果存在）。
     *
     * @param executionId 执行 ID
     * @return 恢复结果（包含已保存的状态），如果不存在返回 {@code Optional.empty()}
     */
    public Optional<RestoredState> restoreCheckpoint(String executionId) {
        if (executionId == null || executionId.isBlank()) return Optional.empty();

        try {
            String json = redisTemplate.opsForValue().get(checkpointKey(executionId));
            if (json == null || json.isBlank()) return Optional.empty();

            GraphExecutionSnapshot snapshot = objectMapper.readValue(json, GraphExecutionSnapshot.class);
            if (snapshot == null) return Optional.empty();

            RestoredState state = restoreFromSnapshot(snapshot);
            log.info("[Checkpoint] 已恢复: executionId={}, round={}, completed={}",
                    executionId, snapshot.getRound(),
                    snapshot.getCompletedMap() != null ? snapshot.getCompletedMap().size() : 0);
            return Optional.of(state);
        } catch (Exception e) {
            log.warn("[Checkpoint] 恢复失败: executionId={}, error={}", executionId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 删除指定执行 ID 的检查点（执行完成后清理）。
     */
    public void deleteCheckpoint(String executionId) {
        if (executionId == null || executionId.isBlank()) return;
        try {
            redisTemplate.delete(checkpointKey(executionId));
            log.debug("[Checkpoint] 已删除: executionId={}", executionId);
        } catch (Exception e) {
            log.warn("[Checkpoint] 删除失败: executionId={}", executionId);
        }
    }

    // ==================== 内部方法 ====================

    private String checkpointKey(String executionId) {
        return CHECKPOINT_KEY_PREFIX + executionId;
    }

    private GraphExecutionSnapshot buildSnapshot(String executionId, IntentGraph graph,
                                                  ConcurrentHashMap<String, SubTaskResult> completedMap,
                                                  ConcurrentHashMap<String, Integer> breakerFailureCounts,
                                                  int round, int staleRounds, int previousCompleted,
                                                  Long userId, String eventsKey, String requestId) {
        GraphExecutionSnapshot snapshot = new GraphExecutionSnapshot();
        snapshot.setExecutionId(executionId);

        // 序列化 Graph
        if (graph != null) {
            List<NodeData> nodes = graph.getAllNodes().stream()
                    .map(n -> {
                        NodeData nd = new NodeData();
                        nd.setId(n.getId());
                        nd.setDescription(n.getDescription());
                        nd.setTargetAgent(n.getTargetAgent());
                        nd.setDependsOn(n.getDependsOn());
                        nd.setSuccessCriteria(n.getSuccessCriteria());
                        nd.setHumanApprovalRequired(n.isHumanApprovalRequired());
                        // 条件依赖
                        var conds = n.getConditionalDeps();
                        if (conds != null && !conds.isEmpty()) {
                            nd.setConditionalDeps(conds.stream()
                                    .map(cd -> {
                                        ConditionalDepData cdd = new ConditionalDepData();
                                        cdd.setSourceNodeId(cd.sourceNodeId());
                                        cdd.setConditionType(cd.conditionType().name());
                                        cdd.setConditionValue(cd.conditionValue());
                                        return cdd;
                                    })
                                    .collect(Collectors.toList()));
                        }
                        return nd;
                    })
                    .collect(Collectors.toList());
            snapshot.setGraphData(new GraphData(graph.getQuestion(), nodes));
        }

        // 序列化 completedMap
        if (completedMap != null) {
            Map<String, SubTaskResultData> serialized = new LinkedHashMap<>();
            for (Map.Entry<String, SubTaskResult> entry : completedMap.entrySet()) {
                serialized.put(entry.getKey(), new SubTaskResultData(entry.getValue()));
            }
            snapshot.setCompletedMap(serialized);
            snapshot.setBreakerFailureCounts(new HashMap<>(breakerFailureCounts != null ? breakerFailureCounts : Map.of()));
        } else {
            snapshot.setCompletedMap(Map.of());
            snapshot.setBreakerFailureCounts(Map.of());
        }

        snapshot.setRound(round);
        snapshot.setStaleRounds(staleRounds);
        snapshot.setPreviousCompleted(previousCompleted);
        snapshot.setUserId(userId);
        snapshot.setEventsKey(eventsKey);
        snapshot.setRequestId(requestId);

        return snapshot;
    }

    private RestoredState restoreFromSnapshot(GraphExecutionSnapshot snapshot) {
        // 恢复 Graph
        IntentGraph restoredGraph = null;
        if (snapshot.getGraphData() != null) {
            GraphData gd = snapshot.getGraphData();
            List<IntentGraph.IntentNode> nodes = gd.getNodes().stream()
                    .map(nd -> {
                        // 反序列化条件依赖
                        List<IntentGraph.IntentNode.ConditionalDependency> conds = List.of();
                        if (nd.getConditionalDeps() != null && !nd.getConditionalDeps().isEmpty()) {
                            conds = nd.getConditionalDeps().stream()
                                    .map(cdd -> new IntentGraph.IntentNode.ConditionalDependency(
                                            cdd.getSourceNodeId(),
                                            IntentGraph.ConditionType.valueOf(cdd.getConditionType()),
                                            cdd.getConditionValue()))
                                    .collect(Collectors.toList());
                        }
                        return new IntentGraph.IntentNode(
                                nd.getId(), nd.getDescription(), nd.getTargetAgent(),
                                nd.getDependsOn(), nd.getSuccessCriteria(),
                                conds, nd.isHumanApprovalRequired());
                    })
                    .collect(Collectors.toList());
            restoredGraph = new IntentGraph(gd.getQuestion(), nodes);
        }

        // 恢复 completedMap
        ConcurrentHashMap<String, SubTaskResult> restoredCompleted = new ConcurrentHashMap<>();
        if (snapshot.getCompletedMap() != null) {
            for (Map.Entry<String, SubTaskResultData> entry : snapshot.getCompletedMap().entrySet()) {
                SubTaskResultData d = entry.getValue();
                restoredCompleted.put(entry.getKey(), new SubTaskResult(
                        d.getTaskId(), d.getDescription(), d.getAgentName(),
                        d.getResult(), d.isSuccess(),
                        SubTaskResult.ErrorType.valueOf(d.getErrorType())));
            }
        }

        // 恢复熔断计数器
        ConcurrentHashMap<String, Integer> restoredBreakers = new ConcurrentHashMap<>();
        if (snapshot.getBreakerFailureCounts() != null) {
            restoredBreakers.putAll(snapshot.getBreakerFailureCounts());
        }

        return new RestoredState(
                restoredGraph, restoredCompleted, restoredBreakers,
                snapshot.getRound(), snapshot.getStaleRounds(),
                snapshot.getPreviousCompleted(), snapshot.getUserId(),
                snapshot.getEventsKey(), snapshot.getRequestId());
    }

    // ==================== 恢复结果 ====================

    /**
     * 从 Checkpoint 恢复的执行状态。
     */
    public static class RestoredState {
        private final IntentGraph graph;
        private final ConcurrentHashMap<String, SubTaskResult> completedMap;
        private final ConcurrentHashMap<String, Integer> breakerFailureCounts;
        private final int round;
        private final int staleRounds;
        private final int previousCompleted;
        private final Long userId;
        private final String eventsKey;
        private final String requestId;

        public RestoredState(IntentGraph graph,
                              ConcurrentHashMap<String, SubTaskResult> completedMap,
                              ConcurrentHashMap<String, Integer> breakerFailureCounts,
                              int round, int staleRounds, int previousCompleted,
                              Long userId, String eventsKey, String requestId) {
            this.graph = graph;
            this.completedMap = completedMap;
            this.breakerFailureCounts = breakerFailureCounts;
            this.round = round;
            this.staleRounds = staleRounds;
            this.previousCompleted = previousCompleted;
            this.userId = userId;
            this.eventsKey = eventsKey;
            this.requestId = requestId;
        }

        public IntentGraph getGraph() { return graph; }
        public ConcurrentHashMap<String, SubTaskResult> getCompletedMap() { return completedMap; }
        public ConcurrentHashMap<String, Integer> getBreakerFailureCounts() { return breakerFailureCounts; }
        public int getRound() { return round; }
        public int getStaleRounds() { return staleRounds; }
        public int getPreviousCompleted() { return previousCompleted; }
        public Long getUserId() { return userId; }
        public String getEventsKey() { return eventsKey; }
        public String getRequestId() { return requestId; }
    }
}
