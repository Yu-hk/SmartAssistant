/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具组管理器——管理 {@link ToolGroup} 的注册和按需激活。
 * <p>
 * 核心能力：
 * <ol>
 *   <li><b>分组注册</b>：将工具按功能域注册到不同组中</li>
 *   <li><b>按需激活</b>：根据当前任务只激活相关组的工具，减少 LLM Schema 占用</li>
 *   <li><b>Meta-Tool</b>：提供 enableGroup/disableGroup 让 LLM 在运行时自主切换</li>
 * </ol>
 * </p>
 *
 * <p>与 {@link SmartReActAgent} 配合使用：Agent 启动时注册所有组，
 * ReAct 循环前根据任务激活特定组，获取活跃工具列表后执行。</p>
 */
public class ToolGroupManager {

    private static final Logger log = LoggerFactory.getLogger(ToolGroupManager.class);

    private final Map<String, ToolGroup> groups = new LinkedHashMap<>();
    private final Set<String> activeGroups = new LinkedHashSet<>();
    private boolean initialized = false;

    /**
     * 注册一个工具组（必须先注册，再激活）。
     */
    public ToolGroupManager register(ToolGroup group) {
        if (groups.containsKey(group.getName())) {
            log.warn("[ToolGroup] 组名重复，覆盖: {}", group.getName());
        }
        groups.put(group.getName(), group);
        log.info("[ToolGroup] 已注册组: name={}, tools={}", group.getName(), group.getToolNames());
        return this;
    }

    /**
     * 注册多个工具组。
     */
    public ToolGroupManager register(ToolGroup... groups) {
        for (var g : groups) register(g);
        return this;
    }

    /**
     * 激活指定组及其工具。
     */
    public ToolGroupManager activate(String... groupNames) {
        for (String name : groupNames) {
            if (!groups.containsKey(name)) {
                log.warn("[ToolGroup] 尝试激活不存在的组: {}", name);
                continue;
            }
            activeGroups.add(name);
        }
        return this;
    }

    /**
     * 激活所有必选组。
     */
    public ToolGroupManager activateRequired() {
        for (var entry : groups.entrySet()) {
            if (entry.getValue().isRequired()) {
                activeGroups.add(entry.getKey());
            }
        }
        return this;
    }

    /**
     * 取消激活指定组。
     */
    public ToolGroupManager deactivate(String... groupNames) {
        for (String name : groupNames) {
            activeGroups.remove(name);
        }
        return this;
    }

    /**
     * 获取当前所有活跃的工具列表（已激活组的工具合集）。
     */
    public List<ToolCallback> getActiveTools() {
        if (activeGroups.isEmpty()) {
            // 没有显式激活任何组时，返回所有工具（兼容旧行为）
            return getAllTools();
        }
        return activeGroups.stream()
                .filter(groups::containsKey)
                .flatMap(name -> groups.get(name).getTools().stream())
                .collect(Collectors.toList());
    }

    /**
     * 获取所有组的全部工具。
     */
    public List<ToolCallback> getAllTools() {
        return groups.values().stream()
                .flatMap(g -> g.getTools().stream())
                .collect(Collectors.toList());
    }

    /** 获取所有组名 */
    public Set<String> getGroupNames() {
        return groups.keySet();
    }

    /** 获取当前活跃组名 */
    public Set<String> getActiveGroupNames() {
        return Collections.unmodifiableSet(activeGroups);
    }

    /** 获取指定组的工具 */
    public List<ToolCallback> getTools(String groupName) {
        ToolGroup g = groups.get(groupName);
        return g != null ? g.getTools() : Collections.emptyList();
    }

    /** 总活跃工具数 */
    public int getActiveToolCount() {
        return getActiveTools().size();
    }

    /** Meta-Tool：启用指定组（供 LLM 运行时调用） */
    public String enableGroup(String groupName) {
        if (!groups.containsKey(groupName)) {
            return "{\"error_code\":\"GROUP_NOT_FOUND\",\"message\":\"组 " + groupName + " 不存在\"}";
        }
        activeGroups.add(groupName);
        log.info("[ToolGroup] Meta-Tool 启用组: {}, 活跃工具数: {}", groupName, getActiveToolCount());
        return "{\"result\":\"已启用组 " + groupName + "\",\"active_tools\":" + getActiveToolCount() + "}";
    }

    /** Meta-Tool：禁用指定组（供 LLM 运行时调用） */
    public String disableGroup(String groupName) {
        ToolGroup g = groups.get(groupName);
        if (g == null) {
            return "{\"error_code\":\"GROUP_NOT_FOUND\",\"message\":\"组 " + groupName + " 不存在\"}";
        }
        if (g.isRequired()) {
            return "{\"error_code\":\"GROUP_REQUIRED\",\"message\":\"组 " + groupName + " 为必选，不可禁用\"}";
        }
        activeGroups.remove(groupName);
        log.info("[ToolGroup] Meta-Tool 禁用组: {}, 活跃工具数: {}", groupName, getActiveToolCount());
        return "{\"result\":\"已禁用组 " + groupName + "\",\"active_tools\":" + getActiveToolCount() + "}";
    }

    /** 获取当前活跃组的描述（用于注入 Agent 提示词） */
    public String getActiveGroupsDescription() {
        if (activeGroups.isEmpty()) {
            return "全部工具组已加载";
        }
        return activeGroups.stream()
                .filter(groups::containsKey)
                .map(name -> {
                    ToolGroup g = groups.get(name);
                    return "- " + name + ": " + g.getDescription() + " (" + g.size() + " 个工具)";
                })
                .collect(Collectors.joining("\n"));
    }
}
