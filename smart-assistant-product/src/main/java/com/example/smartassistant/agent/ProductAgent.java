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
 * 商品/美食推荐 Agent — 声明式接口。
 * <p>
 * 替代 {@code FoodRecommendationAgentConfig.java} 的全部手写配置代码。
 * </p>
 */
@SmartAgent(
    name = "product_agent",
    systemPrompt = "prompts/food-system-prompt.txt",
    maxIterations = 10,
    timeoutMs = 60_000
)
public interface ProductAgent {

    /**
     * 商品/美食推荐入口。
     *
     * @param message 用户消息
     * @return Agent 回复
     */
    String chat(@UserMessage String message);
}
