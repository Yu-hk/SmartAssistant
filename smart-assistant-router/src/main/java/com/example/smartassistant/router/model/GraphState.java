/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.model;

import java.util.*;
import java.util.function.BinaryOperator;

/**
 * Graph 执行状态 — 类型化的共享 State，支持 Reducer 语义。
 * <p>
 * 对标 LangGraph 的 {@code MessagesState} + Reducer 归纳函数概念。
 * 提供三种 reducer 模式：
 * <ul>
 *   <li><b>覆盖 (OVERWRITE)</b>：新值替换旧值（默认，类似不指定归纳函数）</li>
 *   <li><b>追加 (APPEND)</b>：新值追加到旧值（类似 {@code operator.add}）</li>
 *   <li><b>聚合 (MERGE)</b>：自定义 BinaryOperator 合并新旧值</li>
 * </ul>
 * </p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * GraphState state = new GraphState("用户问题");
 *
 * // 追加消息（类似 LangGraph 的 add_messages）
 * state.append("messages", "第一条结果");
 * state.append("messages", "第二条结果");
 * // messages = ["第一条结果", "第二条结果"]
 *
 * // 覆盖共享数据（类似不指定归纳函数）
 * state.set("weather", "晴天");
 * state.set("weather", "多云");
 * // weather = "多云"（覆盖）
 * }</pre>
 *
 * @author Yu-hk
 * @since 2026-07-07
 */
public class GraphState {

    /** 原始用户问题 */
    private final String question;

    /** 共享数据存储（支持各种 Reducer 模式） */
    private final Map<String, Object> data;

    /** Reducer 函数注册表：fieldName → reducer */
    private final Map<String, ReducerMode> reducerModes;

    /** 节点执行记录追踪 */
    private final List<NodeExecutionRecord> executionRecords;

    public GraphState(String question) {
        this.question = question;
        this.data = new LinkedHashMap<>();
        this.reducerModes = new HashMap<>();
        this.executionRecords = new ArrayList<>();
    }

    // ==================== Reducer 模式 ====================

    /**
     * Reducer 模式 — 决定数据字段的更新方式。
     */
    public enum ReducerMode {
        /** 覆盖：新值替换旧值（默认，同 LangGraph 不指定归纳函数） */
        OVERWRITE,
        /** 追加：追加到列表末尾（同 LangGraph 的 operator.add） */
        APPEND,
        /** 聚合内部 Map：合并两个 Map（同 key 覆盖） */
        MERGE_MAP
    }

    // ==================== 数据操作 ====================

    /**
     * 设置值（使用指定 Reducer 模式）。
     */
    @SuppressWarnings("unchecked")
    public void set(String key, Object value) {
        ReducerMode mode = reducerModes.getOrDefault(key, ReducerMode.OVERWRITE);
        switch (mode) {
            case APPEND -> {
                List<Object> list = (List<Object>) data.computeIfAbsent(key, k -> new ArrayList<>());
                if (value instanceof Collection) {
                    list.addAll((Collection<Object>) value);
                } else {
                    list.add(value);
                }
            }
            case MERGE_MAP -> {
                Map<String, Object> existing = (Map<String, Object>) data.computeIfAbsent(key, k -> new LinkedHashMap<>());
                if (value instanceof Map) {
                    existing.putAll((Map<String, Object>) value);
                }
            }
            default -> data.put(key, value); // OVERWRITE
        }
    }

    /**
     * 覆盖模式设置值（直接覆盖，不受 Reducer 注册影响）。
     */
    public void overwrite(String key, Object value) {
        data.put(key, value);
    }

    /**
     * 追加模式添加值（追加到列表）。
     */
    @SuppressWarnings("unchecked")
    public void append(String key, Object value) {
        List<Object> list = (List<Object>) data.computeIfAbsent(key, k -> new ArrayList<>());
        list.add(value);
    }

    /**
     * 获取值。
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) data.get(key);
    }

    /**
     * 获取值，不存在时返回默认值。
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String key, T defaultValue) {
        return (T) data.getOrDefault(key, defaultValue);
    }

    /**
     * 注册字段的 Reducer 模式。
     */
    public void registerReducer(String key, ReducerMode mode) {
        reducerModes.put(key, mode);
    }

    /**
     * 检查是否包含某 key。
     */
    public boolean containsKey(String key) {
        return data.containsKey(key);
    }

    /**
     * 获取所有 key。
     */
    public Set<String> keySet() {
        return data.keySet();
    }

    // ==================== 消息流（常用模式） ====================

    /**
     * 添加一条 Agent 消息到消息列表（自动追加模式）。
     */
    public void addMessage(String agentName, String content) {
        append("messages", Map.of("agent", agentName, "content", content, "timestamp", System.currentTimeMillis()));
    }

    /**
     * 获取所有消息。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getMessages() {
        return getOrDefault("messages", List.of());
    }

    // ==================== 节点执行记录 ====================

    /**
     * 记录节点执行。
     */
    public void recordExecution(String nodeId, String agentName, boolean success, String result) {
        executionRecords.add(new NodeExecutionRecord(nodeId, agentName, success, result, System.currentTimeMillis()));
    }

    /**
     * 获取所有执行记录。
     */
    public List<NodeExecutionRecord> getExecutionRecords() {
        return List.copyOf(executionRecords);
    }

    /**
     * 获取特定节点的执行记录。
     */
    public List<NodeExecutionRecord> getRecordsForNode(String nodeId) {
        return executionRecords.stream()
                .filter(r -> r.nodeId().equals(nodeId))
                .toList();
    }

    public String getQuestion() { return question; }

    // ==================== 内部类型 ====================

    /**
     * 节点执行记录。
     */
    public record NodeExecutionRecord(
            String nodeId,
            String agentName,
            boolean success,
            String result,
            long timestamp
    ) {}

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("GraphState{question='").append(question).append("', fields=[");
        sb.append(String.join(", ", data.keySet()));
        sb.append("], records=").append(executionRecords.size()).append("}");
        return sb.toString();
    }
}
