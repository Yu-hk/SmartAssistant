/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SSE 流式事件，作为 {@code text/event-stream} 的 JSON 负载。
 * <p>SSE 格式：</p>
 * <pre>
 * event: agent_complete
 * data: {"type":"agent_complete","content":"...","agentName":"...","finished":false}
 *
 * event: agent_stream_delta
 * data: {"type":"agent_stream_delta","content":"","finished":true}
 * </pre>
 *
 * <p>事件类型（对应 SSE {@code event:} 字段及 JSON {@code type} 字段）：</p>
 * <ul>
 *   <li>{@code TYPE_DELTA} / {@code agent_stream_delta} — 流式文本增量（拼接到前端缓冲区）</li>
 *   <li>{@code TYPE_TOOL_CALL} / {@code agent_tool_call} — Agent 调用了工具（含工具名+参数）</li>
 *   <li>{@code TYPE_TOOL_RESULT} / {@code agent_tool_result} — 工具返回结果</li>
 *   <li>{@code TYPE_COMPLETE} / {@code agent_complete} — Agent 结束（含完整回复和 metadata）</li>
 *   <li>{@code TYPE_ERROR} / {@code agent_error} — 错误事件</li>
 *   <li>{@code TYPE_WAITING} / {@code agent_waiting} — 等待路由决策</li>
 *   <li>{@code TYPE_PROCESSING} / {@code agent_processing} — LLM 槽位已分配</li>
 * </ul>
 *
 * <p>基础设施事件（无 Agent 语义，不封入此类）：</p>
 * <ul>
 *   <li>{@code queued} / {@code queue_position} — 请求排队</li>
 *   <li>{@code timeout} — 决策超时</li>
 *   <li>{@code done} — 流结束</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentStreamEvent {

    // ==================== 事件类型常量 ====================

    /** 流式文本增量 */
    public static final String TYPE_DELTA = "agent_stream_delta";
    /** 工具调用 */
    public static final String TYPE_TOOL_CALL = "agent_tool_call";
    /** 工具返回结果 */
    public static final String TYPE_TOOL_RESULT = "agent_tool_result";
    /** Agent 完成 */
    public static final String TYPE_COMPLETE = "agent_complete";
    /** 错误 */
    public static final String TYPE_ERROR = "agent_error";
    /** 等待路由决策 */
    public static final String TYPE_WAITING = "agent_waiting";
    /** 处理中（槽位已分配） */
    public static final String TYPE_PROCESSING = "agent_processing";

    /** 事件类型 */
    private String type;

    /** 文本内容（delta 或完整回复） */
    private String content;

    /** 处理 Agent 名称 */
    private String agentName;

    /** 工具名称（tool_call/tool_result 用） */
    private String toolName;

    /** 工具输入参数（tool_call 用） */
    private String toolInput;

    /** 工具返回结果（tool_result 用） */
    private String toolResult;

    /** 是否结束（agent_stream_delta 用） */
    private Boolean finished;

    /** 意图标签（agent_complete 用） */
    private String intentTag;

    /** 后续建议列表（agent_complete 用） */
    private java.util.List<String> suggestions;

    /** 错误码（agent_error 用） */
    private String errorCode;

    /** Token 消耗统计（agent_complete 用，仅同步响应后有值） */
    private TokenUsage usage;

    /** 模型名称（agent_complete 用） */
    private String modelName;

    // ---- 工厂方法 ----

    /** 流式文本增量。前端应 append 到缓冲区。 */
    public static AgentStreamEvent delta(String content, boolean finished) {
        AgentStreamEvent e = new AgentStreamEvent();
        e.type = TYPE_DELTA;
        e.content = content;
        e.finished = finished;
        return e;
    }

    /** Agent 调用了工具。 */
    public static AgentStreamEvent toolCall(String toolName, String toolInput, String agentName) {
        AgentStreamEvent e = new AgentStreamEvent();
        e.type = TYPE_TOOL_CALL;
        e.toolName = toolName;
        e.toolInput = toolInput;
        e.agentName = agentName;
        return e;
    }

    /** 工具返回结果。 */
    public static AgentStreamEvent toolResult(String toolName, String toolResult, String agentName) {
        AgentStreamEvent e = new AgentStreamEvent();
        e.type = TYPE_TOOL_RESULT;
        e.toolName = toolName;
        e.toolResult = toolResult;
        e.agentName = agentName;
        return e;
    }

    /** Agent 执行完成（含完整回复和 metadata）。 */
    public static AgentStreamEvent complete(String content, String agentName,
                                             String intentTag,
                                             java.util.List<String> suggestions) {
        return complete(content, agentName, intentTag, suggestions, null, null);
    }

    /** Agent 执行完成（含完整回复、metadata 和 token 消耗）。 */
    public static AgentStreamEvent complete(String content, String agentName,
                                             String intentTag,
                                             java.util.List<String> suggestions,
                                             TokenUsage usage, String modelName) {
        AgentStreamEvent e = new AgentStreamEvent();
        e.type = TYPE_COMPLETE;
        e.content = content;
        e.agentName = agentName;
        e.intentTag = intentTag;
        e.suggestions = suggestions;
        e.usage = usage;
        e.modelName = modelName;
        return e;
    }

    /** 错误事件。 */
    public static AgentStreamEvent error(String content, String errorCode, String agentName) {
        AgentStreamEvent e = new AgentStreamEvent();
        e.type = TYPE_ERROR;
        e.content = content;
        e.errorCode = errorCode;
        e.agentName = agentName;
        return e;
    }

    /** 等待路由决策事件（基础设施用）。 */
    public static AgentStreamEvent waiting(String content) {
        AgentStreamEvent e = new AgentStreamEvent();
        e.type = TYPE_WAITING;
        e.content = content;
        return e;
    }

    /** 处理中事件（LLM 槽位已分配，基础设施用）。 */
    public static AgentStreamEvent processing() {
        AgentStreamEvent e = new AgentStreamEvent();
        e.type = TYPE_PROCESSING;
        return e;
    }

    // ---- Getters ----

    public String getType() { return type; }
    public String getContent() { return content; }
    public String getAgentName() { return agentName; }
    public String getToolName() { return toolName; }
    public String getToolInput() { return toolInput; }
    public String getToolResult() { return toolResult; }
    public Boolean getFinished() { return finished; }
    public String getIntentTag() { return intentTag; }
    public java.util.List<String> getSuggestions() { return suggestions; }
    public String getErrorCode() { return errorCode; }
    public TokenUsage getUsage() { return usage; }
    public String getModelName() { return modelName; }
}
