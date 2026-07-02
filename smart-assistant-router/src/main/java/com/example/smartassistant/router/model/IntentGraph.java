/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.model;

import java.util.*;
import java.util.stream.Collectors;

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

    public IntentGraph(String question, List<IntentNode> nodes) {
        this.question = question;
        this.nodeMap = new LinkedHashMap<>();
        this.adjacency = new HashMap<>();
        this.inDegree = new HashMap<>();

        if (nodes != null) {
            for (IntentNode node : nodes) {
                nodeMap.put(node.getId(), node);
                adjacency.put(node.getId(), new ArrayList<>());
            }
            // 构建邻接表和入度
            for (IntentNode node : nodes) {
                List<String> deps = node.getDependsOn();
                if (deps != null) {
                    for (String depId : deps) {
                        adjacency.computeIfAbsent(depId, k -> new ArrayList<>()).add(node.getId());
                    }
                    inDegree.put(node.getId(), deps.size());
                } else {
                    inDegree.put(node.getId(), 0);
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
     * 获取当前可执行节点（所有依赖已完成的节点）。
     *
     * @param completedNodeIds 已完成节点 ID 集合
     * @return 可执行节点列表
     */
    public List<IntentNode> getExecutableNodes(Set<String> completedNodeIds) {
        if (completedNodeIds == null || completedNodeIds.isEmpty()) {
            return getRootNodes();
        }
        return nodeMap.values().stream()
                .filter(n -> !completedNodeIds.contains(n.getId()))
                .filter(n -> {
                    List<String> deps = n.getDependsOn();
                    return deps == null || deps.isEmpty() || completedNodeIds.containsAll(deps);
                })
                .collect(Collectors.toList());
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
     * 死锁检测：检查是否有可执行节点但仍有未完成节点。
     */
    public boolean hasDeadlock(Set<String> completedNodeIds) {
        if (isCompleted(completedNodeIds)) return false;
        if (completedNodeIds == null || completedNodeIds.isEmpty()) {
            return getRootNodes().isEmpty() && !nodeMap.isEmpty();
        }
        // 如果还有未完成节点但没有可执行的，就是死锁
        return getExecutableNodes(completedNodeIds).isEmpty();
    }

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
     * 意图图节点，表示一个可执行的子任务。
     */
    public static class IntentNode {
        private final String id;
        private final String description;
        private final String targetAgent;
        private final List<String> dependsOn;
        /** ⭐ 验收标准（来自 SubTask.successCriteria） */
        private final String successCriteria;

        public IntentNode(String id, String description, String targetAgent, List<String> dependsOn) {
            this(id, description, targetAgent, dependsOn, null);
        }

        public IntentNode(String id, String description, String targetAgent, List<String> dependsOn, String successCriteria) {
            this.id = id;
            this.description = description;
            this.targetAgent = targetAgent;
            this.dependsOn = dependsOn != null ? dependsOn : List.of();
            this.successCriteria = successCriteria;
        }

        public String getId() { return id; }
        public String getDescription() { return description; }
        public String getTargetAgent() { return targetAgent; }
        public List<String> getDependsOn() { return dependsOn; }
        public String getSuccessCriteria() { return successCriteria; }

        @Override
        public String toString() {
            return String.format("IntentNode(%s|%s|%s|deps=%s)", id, description, targetAgent, dependsOn);
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
