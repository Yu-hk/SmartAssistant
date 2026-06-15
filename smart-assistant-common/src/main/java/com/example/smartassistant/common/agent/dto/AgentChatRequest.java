/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent.dto;

/**
 * REST 方式调用下游 Agent 的请求体。
 * <p>替代 A2A 协议中 A2aRemoteAgent.invoke() 的参数传递。</p>
 *
 * @param message   经过 Router 优化/注入 requestId 后的指令文本
 * @param userId    用户 ID
 * @param requestId 可选的请求追踪 ID（透传用于 @Tool 日志关联）
 */
public record AgentChatRequest(String message, Long userId, String requestId) {
}
