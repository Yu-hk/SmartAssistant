/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.model;

import java.util.*;
import java.util.stream.Collectors;

import com.example.smartassistant.router.model.SubTaskResult;

/**
 * 多意图拆解图结构。
 * <p>
 * 将用户的多意图问题拆解为依赖感知的有向无环图（DAG），
 * 支持：
 * <ul>
 *   <li>无依赖节点并行执行（如"查天气"+"查新闻"互不依赖，可同时进行）</li>
 *   <li>有依赖节点顺序执行且共享上下文（如"先查景点，再推荐景点附近餐厅"）</li>
 *   <li>死锁检测：循环依赖或不可达节点自动跳过</li>
 * </ul>
 * </p>
 */
public class IntentGraph {

    /** 原始用户问题 */
    private final String question;

    /** 所有任务节点，按 id 索引 */
    private final Map<String, IntentNode> nodeMap;

    /** 邻接表：nodeId → 依赖的 nodeId 列表 */
    private final Map<String, List<String>> adjacency;

    /** 入度表：nodeId → 尚未完成的依赖数 */
    private final Map<String, Integer> inDegree;

    /** 条件依赖表：nodeId → 条件依赖列表（仅条件依赖的节点） */
    private final Map<String, List<ConditionalDep>> conditionalDeps;

    /** 重路由表：nodeId → 条件满足时重路由到的目标节点（用于循环） */
    private final Map<String, String> rerouteTargets;

    /** 最大图迭代预算（防止无限循环，0=无限） */
    private final int maxGraphIterations;

    public IntentGraph(String question, List<IntentNode> nodes) {
        this(question, nodes, 0);
    }

    public IntentGraph(String question, List<IntentNode> nodes, int maxGraphIterations) {
        this.question = question;
        this.nodeMap = new LinkedHashMap<>();
        this.adjacency = new HashMap<>();
        this.inDegree = new HashMap<>();
        this.conditionalDeps = new HashMap<>();
        this.rerouteTargets = new HashMap<>();
        this.maxGraphIterations = maxGraphIterations > 0 ? maxGraphIterations : 0;

        if (nodes != null) {
            for (IntentNode node : nodes) {
                nodeMap.put(node.getId(), node);
                adjacency.put(node.getId(), new ArrayList<>());
                conditionalDeps.put(node.getId(), new ArrayList<>());
            }
            // 构建邻接表和入度
            for (IntentNode node : nodes) {
                List<String> deps = node.getDependsOn();
                List<IntentNode.ConditionalDependency> condDeps = node.getConditionalDeps();

                // 普通依赖
                if (deps != null) {
                    for (String depId : deps) {
                        adjacency.computeIfAbsent(depId, k -> new ArrayList<>()).add(node.getId());
                    }
                    inDegree.put(node.getId(), deps.size());
                } else {
                    inDegree.put(node.getId(), 0);
                }

                // 条件依赖
                if (condDeps != null && !condDeps.isEmpty()) {
                    for (IntentNode.ConditionalDependency cd : condDeps) {
                        String rerouteTarget = cd.rerouteTarget();
                        if (rerouteTarget != null && !rerouteTarget.isEmpty()) {
                            // 这是一个重路由条件：条件满足时跳转到目标节点（形成循环）
                            rerouteTargets.put(node.getId(), rerouteTarget);
                        }
                        conditionalDeps.computeIfAbsent(node.getId(), k -> new ArrayList<>())
                                .add(new ConditionalDep(cd.sourceNodeId(), cd.conditionType(), cd.conditionValue(), rerouteTarget));
                    }
                }
            }
        }
    }

    // ==================== 拓扑查询 ====================

    /**
     * 获取初始可执行节点（入度为 0，无依赖）。
     */
    public List<IntentNode> getRootNodes() {
        return nodeMap.values().stream()
                .filter(n -> inDegree.getOrDefault(n.getId(), 0) == 0)
                .collect(Collectors.toList());
    }

    /**
     * 获取当前可执行节点（所有依赖已完成的节点，条件依赖需满足条件）。
     * <p>
     * 如果节点有重路由条件（rerouteTarget），即使条件满足也不在此返回
     * （由执行引擎单独处理重路由，节点自身不执行）。
     * </p>
     *
     * @param completedNodeIds 已完成节点 ID 集合
     * @param nodeResults      已完成节点的结果映射（用于条件依赖评估），可为 null
     * @return 可执行节点列表
     */
    public List<IntentNode> getExecutableNodes(Set<String> completedNodeIds,
                                                Map<String, SubTaskResult> nodeResults) {
        if (completedNodeIds == null || completedNodeIds.isEmpty()) {
            return getRootNodes();
        }
        return nodeMap.values().stream()
                .filter(n -> !completedNodeIds.contains(n.getId()))
                // 排除仅用于重路由的节点（rerouteTarget 非空，本身不执行）
                .filter(n -> !rerouteTargets.containsKey(n.getId()))
                .filter(n -> {
                    List<String> deps = n.getDependsOn();
                    if (deps == null || deps.isEmpty()) {
                        List<ConditionalDep> condDeps = conditionalDeps.getOrDefault(n.getId(), List.of());
                        if (condDeps.isEmpty()) return true;
                        return condDeps.stream().allMatch(cd -> evaluateCondition(cd, completedNodeIds, nodeResults));
                    }
                    if (!completedNodeIds.containsAll(deps)) return false;
                    List<ConditionalDep> condDeps = conditionalDeps.getOrDefault(n.getId(), List.of());
                    if (condDeps.isEmpty()) return true;
                    return condDeps.stream().allMatch(cd -> evaluateCondition(cd, completedNodeIds, nodeResults));
                })
                .collect(Collectors.toList());
    }

    /**
     * 评估条件依赖。
     */
    private boolean evaluateCondition(ConditionalDep cd, Set<String> completedNodeIds,
                                       Map<String, SubTaskResult> nodeResults) {
        // 源节点必须已完成
        if (!completedNodeIds.contains(cd.sourceNodeId)) return false;

        // 获取源节点结果
        String resultText = "";
        if (nodeResults != null) {
            SubTaskResult r = nodeResults.get(cd.sourceNodeId);
            if (r != null && r.getResult() != null) {
                resultText = r.getResult();
            }
        }

        switch (cd.conditionType) {
            case RESULT_CONTAINS:
                return cd.conditionValue != null && resultText.contains(cd.conditionValue);
            case RESULT_NOT_CONTAINS:
                return cd.conditionValue != null && !resultText.contains(cd.conditionValue);
            case RESULT_SUCCESS:
                return nodeResults != null && nodeResults.containsKey(cd.sourceNodeId)
                        && nodeResults.get(cd.sourceNodeId).isSuccess();
            case RESULT_FAILED:
                return nodeResults != null && nodeResults.containsKey(cd.sourceNodeId)
                        && !nodeResults.get(cd.sourceNodeId).isSuccess();
            default:
                return true;
        }
    }

    /**
     * 获取当前可执行节点（不使用 nodeResults，条件依赖默认视为满足）。
     */
    public List<IntentNode> getExecutableNodes(Set<String> completedNodeIds) {
        return getExecutableNodes(completedNodeIds, null);
    }

    /**
     * 所有节点是否已完成。
     */
    public boolean isCompleted(Set<String> completedNodeIds) {
        if (completedNodeIds == null) return false;
        return nodeMap.keySet().stream().allMatch(completedNodeIds::contains);
    }

    /**
     * 获取最大可能并行度（单层最多可并行执行的节点数）。
     */
    public int getMaxParallelism() {
        // 最坏情况：所有根节点之和
        return getRootNodes().size();
    }

    /**
     * 是否存在依赖关系（有向无环图有多层结构）。
     */
    public boolean hasDependency() {
        return nodeMap.values().stream()
                .anyMatch(n -> n.getDependsOn() != null && !n.getDependsOn().isEmpty());
    }

    /**
     * 获取失败节点数（不在 completed 中的节点）。
     */
    public int getPendingCount(Set<String> completedNodeIds) {
        if (completedNodeIds == null) return nodeMap.size();
        return (int) nodeMap.keySet().stream()
                .filter(id -> !completedNodeIds.contains(id))
                .count();
    }

    /**
     * 死锁检测：检查是否有可执行节点但仍有未完成节点（含重路由节点检查）。
     */
    public boolean hasDeadlock(Set<String> completedNodeIds) {
        return hasDeadlock(completedNodeIds, null);
    }

    /**
     * 死锁检测（支持 nodeResults 用于条件评估）。
     */
    public boolean hasDeadlock(Set<String> completedNodeIds, Map<String, SubTaskResult> nodeResults) {
        if (isCompleted(completedNodeIds)) return false;
        if (completedNodeIds == null || completedNodeIds.isEmpty()) {
            return getRootNodes().isEmpty() && !nodeMap.isEmpty();
        }
        // 如果还有未完成节点但没有可执行的（含重路由节点），就是死锁
        List<IntentNode> executable = getExecutableNodes(completedNodeIds, nodeResults);
        boolean hasReroute = checkAnyRerouteMet(completedNodeIds, nodeResults);
        return executable.isEmpty() && !hasReroute;
    }

    /**
     * 检查是否有节点的重路由条件已满足。
     */
    private boolean checkAnyRerouteMet(Set<String> completedNodeIds, Map<String, SubTaskResult> nodeResults) {
        for (String nodeId : rerouteTargets.keySet()) {
            if (completedNodeIds.contains(nodeId)) continue;
            List<ConditionalDep> conds = conditionalDeps.getOrDefault(nodeId, List.of());
            if (conds.isEmpty()) continue;
            // 条件全部满足时可重路由
            boolean allMet = conds.stream().allMatch(cd -> evaluateCondition(cd, completedNodeIds, nodeResults));
            if (allMet) return true;
        }
        return false;
    }

    /**
     * 检查是否有节点触发了重路由。
     *
     * @return 重路由目标节点 ID，如无可重路由节点返回 null
     */
    public String getRerouteTargetIfMet(Set<String> completedNodeIds, Map<String, SubTaskResult> nodeResults) {
        for (Map.Entry<String, String> entry : rerouteTargets.entrySet()) {
            String nodeId = entry.getKey();
            if (completedNodeIds.contains(nodeId)) continue;
            List<ConditionalDep> conds = conditionalDeps.getOrDefault(nodeId, List.of());
            boolean allMet = conds.stream().allMatch(cd -> evaluateCondition(cd, completedNodeIds, nodeResults));
            if (allMet) {
                return entry.getValue();
            }
        }
        return null;
    }

    public int getMaxGraphIterations() { return maxGraphIterations; }

    // ==================== 构造器 ====================

    /**
     * 从旧版扁平 SubTask 列表构建无依赖的 IntentGraph（后向兼容）。
     */
    public static IntentGraph fromFlatTasks(String question, List<SubTask> tasks) {
        List<IntentNode> nodes = tasks.stream()
                .map(t -> new IntentNode(t.getId(), t.getDescription(), t.getTargetAgent(),
                        t.getDependsOn(), t.getSuccessCriteria()))
                .collect(Collectors.toList());
        return new IntentGraph(question, nodes);
    }

    // ==================== 内部类 ====================

    /**
     * 条件依赖定义 — 用于条件边和循环。
     * <p>
     * 当 sourceNode 的结果满足 conditionType + conditionValue 时：
     * <ul>
     *   <li>若 rerouteTarget 非空：将执行流重路由到目标节点（形成 Graph 循环）</li>
     *   <li>若 rerouteTarget 为空：条件依赖视为满足，当前节点可执行</li>
     * </ul>
     * </p>
     */
    public record ConditionalDep(String sourceNodeId, ConditionType conditionType, String conditionValue, String rerouteTarget) {
        public ConditionalDep(String sourceNodeId, ConditionType conditionType, String conditionValue) {
            this(sourceNodeId, conditionType, conditionValue, null);
        }
    }

    /**
     * 条件依赖类型。
     */
    public enum ConditionType {
        /** 源节点结果包含指定值 */
        RESULT_CONTAINS,
        /** 源节点结果不包含指定值 */
        RESULT_NOT_CONTAINS,
        /** 源节点执行成功 */
        RESULT_SUCCESS,
        /** 源节点执行失败 */
        RESULT_FAILED
    }

    /**
     * 意图图节点，表示一个可执行的子任务。
     */
    public static class IntentNode {
        private final String id;
        private final String description;
        private final String targetAgent;
        private final List<String> dependsOn;
        /** ⭐ 条件依赖列表：当源节点结果满足条件时才执行此节点 */
        private final List<ConditionalDependency> conditionalDeps;
        /** ⭐ 验收标准（来自 SubTask.successCriteria） */
        private final String successCriteria;
        /** ⭐ 是否需要人工审批后才执行 */
        private final boolean humanApprovalRequired;

        public IntentNode(String id, String description, String targetAgent, List<String> dependsOn) {
            this(id, description, targetAgent, dependsOn, null, List.of(), false);
        }

        public IntentNode(String id, String description, String targetAgent, List<String> dependsOn, String successCriteria) {
            this(id, description, targetAgent, dependsOn, successCriteria, List.of(), false);
        }

        public IntentNode(String id, String description, String targetAgent, List<String> dependsOn,
                          String successCriteria, List<ConditionalDependency> conditionalDeps,
                          boolean humanApprovalRequired) {
            this.id = id;
            this.description = description;
            this.targetAgent = targetAgent;
            this.dependsOn = dependsOn != null ? dependsOn : List.of();
            this.successCriteria = successCriteria;
            this.conditionalDeps = conditionalDeps != null ? conditionalDeps : List.of();
            this.humanApprovalRequired = humanApprovalRequired;
        }

        public String getId() { return id; }
        public String getDescription() { return description; }
        public String getTargetAgent() { return targetAgent; }
        public List<String> getDependsOn() { return dependsOn; }
        public List<ConditionalDependency> getConditionalDeps() { return conditionalDeps; }
        public String getSuccessCriteria() { return successCriteria; }
        public boolean isHumanApprovalRequired() { return humanApprovalRequired; }

        @Override
        public String toString() {
            return String.format("IntentNode(%s|%s|%s|deps=%s|cond=%s|hitl=%s)",
                    id, description, targetAgent, dependsOn, conditionalDeps, humanApprovalRequired);
        }

        /**
         * 条件依赖关系（与 {@link ConditionalDep} 对应，供构造器使用）。
         * <p>
         * 当 sourceNode 结果满足条件时：
         * <ul>
         *   <li>rerouteTarget 非空：重路由到目标节点（循环）</li>
         *   <li>rerouteTarget 为空：当前节点可执行</li>
         * </ul>
         * </p>
         */
        public record ConditionalDependency(
                String sourceNodeId,
                IntentGraph.ConditionType conditionType,
                String conditionValue,
                String rerouteTarget
        ) {
            public ConditionalDependency(String sourceNodeId, IntentGraph.ConditionType conditionType, String conditionValue) {
                this(sourceNodeId, conditionType, conditionValue, null);
            }
        }
    }

    // ==================== 动态节点追加（用于重规划） ====================

    /**
     * 动态追加节点到图中（重规划场景）。
     * <p>
     * 新节点被加入 nodeMap，并基于其依赖关系重建邻接表和入度。
     * 如果需要为"替换旧节点"语义，调用方应先使用 {@link #removeNode(String)}。
     * </p>
     *
     * @param newNodes 要追加的节点列表
     */
    public void addNodes(List<IntentNode> newNodes) {
        if (newNodes == null || newNodes.isEmpty()) return;
        for (IntentNode node : newNodes) {
            // 避免重复添加
            if (nodeMap.containsKey(node.getId())) {
                log.warn("[IntentGraph] 节点已存在，跳过: {}", node.getId());
                continue;
            }
            nodeMap.put(node.getId(), node);
            adjacency.put(node.getId(), new ArrayList<>());
            conditionalDeps.put(node.getId(), new ArrayList<>());

            // 建立依赖关系
            List<String> deps = node.getDependsOn();
            if (deps != null && !deps.isEmpty()) {
                for (String depId : deps) {
                    adjacency.computeIfAbsent(depId, k -> new ArrayList<>()).add(node.getId());
                }
                inDegree.put(node.getId(), deps.size());
            } else {
                inDegree.put(node.getId(), 0);
            }

            // 条件依赖（含重路由）
            List<IntentNode.ConditionalDependency> condDeps = node.getConditionalDeps();
            if (condDeps != null && !condDeps.isEmpty()) {
                for (IntentNode.ConditionalDependency cd : condDeps) {
                    String rerouteTarget = cd.rerouteTarget();
                    if (rerouteTarget != null && !rerouteTarget.isEmpty()) {
                        rerouteTargets.put(node.getId(), rerouteTarget);
                    }
                    conditionalDeps.computeIfAbsent(node.getId(), k -> new ArrayList<>())
                            .add(new ConditionalDep(cd.sourceNodeId(), cd.conditionType(), cd.conditionValue(), rerouteTarget));
                }
            }
        }
    }

    /**
     * 移除节点（重规划时替换旧节点用）。
     */
    public void removeNode(String nodeId) {
        IntentNode removed = nodeMap.remove(nodeId);
        if (removed == null) return;

        // 清理邻接表
        adjacency.remove(nodeId);
        inDegree.remove(nodeId);
        conditionalDeps.remove(nodeId);
        rerouteTargets.remove(nodeId);

        // 从其他节点的邻接列表中移除引用
        for (List<String> targets : adjacency.values()) {
            targets.remove(nodeId);
        }

        // ⭐ 重新计算受影响的节点入度
        for (IntentNode node : nodeMap.values()) {
            List<String> deps = node.getDependsOn();
            if (deps != null && deps.contains(nodeId)) {
                inDegree.put(node.getId(), Math.max(0, inDegree.getOrDefault(node.getId(), 0) - 1));
            }
        }
    }

    public String getQuestion() { return question; }
    public Collection<IntentNode> getAllNodes() { return nodeMap.values(); }
    public int getNodeCount() { return nodeMap.size(); }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(IntentGraph.class);
}
