/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.general.agent;

import com.example.smartassistant.common.agent.annotation.MemoryId;
import com.example.smartassistant.common.agent.annotation.SmartAgent;
import com.example.smartassistant.common.agent.annotation.UserMessage;

/**
 * 通用对话 Agent — 声明式接口。
 * <p>
 * 替代 {@code GeneralAgentConfig.java} 的全部手写配置代码。
 * </p>
 */
@SmartAgent(
    name = "general_chat",
    systemPrompt = "prompts/general-system-prompt.txt",
    maxIterations = 10,
    timeoutMs = 60_000,
    enableCompress = true,
    compressThreshold = 20
)
public interface GeneralAgent {

    /**
     * 通用对话入口。
     *
     * @param message 用户消息
     * @param userId  用户 ID（用于 @MemoryId 隔离记忆）
     * @return Agent 回复
     */
    String chat(@UserMessage String message, @MemoryId Long userId);
}
