/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.tool;

import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 工具组——对 {@link ToolCallback} 按功能域分组的单元。
 * <p>
 * 参考 AgentScope Java 的 Tool Group 设计。每个工具组有唯一的名称和必选/可选属性，
 * {@link ToolGroupManager} 按需激活相关组，避免 LLM 上下文窗口被无关工具的 Schema 占用。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * ToolGroup orderGroup = ToolGroup.builder("order")
 *     .required(true)
 *     .description("订单操作：查询、支付、取消、退款")
 *     .tools(orderTools)
 *     .build();
 * }</pre>
 */
public class ToolGroup {

    /** 工具组唯一名称 */
    private final String name;

    /** 工具组描述（可用于 Meta-Tool 的提示词） */
    private final String description;

    /** 是否为必选组（true=始终激活，false=按需激活） */
    private final boolean required;

    /** 组内工具列表 */
    private final List<ToolCallback> tools;

    private ToolGroup(String name, String description, boolean required, List<ToolCallback> tools) {
        this.name = name;
        this.description = description;
        this.required = required;
        this.tools = Collections.unmodifiableList(tools);
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isRequired() { return required; }
    public List<ToolCallback> getTools() { return tools; }
    public int size() { return tools.size(); }

    /** 获取所有工具名（用于日志/调试） */
    public List<String> getToolNames() {
        return tools.stream()
                .map(tc -> tc.getToolDefinition().name())
                .toList();
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private String description = "";
        private boolean required = false;
        private final List<ToolCallback> tools = new ArrayList<>();

        Builder(String name) {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("ToolGroup name required");
            this.name = name;
        }

        public Builder description(String description) {
            this.description = description; return this;
        }

        public Builder required(boolean required) {
            this.required = required; return this;
        }

        public Builder tool(ToolCallback tool) {
            this.tools.add(tool); return this;
        }

        public Builder tools(ToolCallback... tools) {
            Collections.addAll(this.tools, tools); return this;
        }

        public Builder tools(List<? extends ToolCallback> tools) {
            this.tools.addAll(tools); return this;
        }

        public ToolGroup build() {
            return new ToolGroup(name, description, required, tools);
        }
    }
}
