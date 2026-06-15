/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.agent;

import com.example.smartassistant.common.agent.annotation.SmartAgent;
import com.example.smartassistant.common.agent.annotation.UserMessage;

/**
 * 订单/出行 Agent — 声明式接口。
 * <p>
 * 替代 {@code TravelAgentConfig.java} 的全部手写配置代码。
 * </p>
 */
@SmartAgent(
    name = "order_agent",
    systemPrompt = "prompts/travel-system-prompt.txt",
    maxIterations = 10,
    timeoutMs = 60_000
)
public interface OrderAgent {

    /**
     * 订单/出行咨询入口。
     *
     * @param message 用户消息
     * @return Agent 回复
     */
    String chat(@UserMessage String message);
}
