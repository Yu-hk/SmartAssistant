/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent.dto;

import java.util.List;
import java.util.Map;

/**
 * REST 方式调用下游 Agent 的响应体。
 * <p>替代 A2A 协议中 A2aRemoteAgent.invoke() 的 OverAllState 返回值。</p>
 *
 * @param success     是否调用成功
 * @param response    Agent 的文本回复
 * @param titles      工具输出中提取的真实标题列表（可选，用于游记/订单场景）
 * @param tagsByTitle 标题对应的标签映射（可选）
 */
public record AgentChatResponse(
        boolean success,
        String response,
        List<String> titles,
        Map<String, String> tagsByTitle) {

    /** 成功响应工厂方法 */
    public static AgentChatResponse ok(String response) {
        return new AgentChatResponse(true, response, List.of(), Map.of());
    }

    /** 成功响应（含标题和标签） */
    public static AgentChatResponse ok(String response, List<String> titles, Map<String, String> tagsByTitle) {
        return new AgentChatResponse(true, response, titles, tagsByTitle);
    }

    /** 失败响应工厂方法 */
    public static AgentChatResponse error(String message) {
        return new AgentChatResponse(false, message, List.of(), Map.of());
    }
}
