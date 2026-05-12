/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Food 流式 Agent 服务
 * <p>
 * 由于 Spring AI Alibaba Graph Core 的流式 API 不稳定，
 * 采用简化的实现：非阻塞调用 + 异步返回结果
 */
@Service
@Slf4j
public class StreamingFoodAgentService {

    private final ReactAgent foodRecommendationAgent;

    public StreamingFoodAgentService(@Qualifier("foodRecommendationAgent") ReactAgent foodRecommendationAgent) {
        this.foodRecommendationAgent = foodRecommendationAgent;
    }

    /**
     * 执行美食推荐（非阻塞）
     */
    public String execute(String userMessage) {
        try {
            log.info("[StreamingFoodAgent] 执行推理: {}", userMessage);
            var response = foodRecommendationAgent.call(userMessage);
            if (response != null) {
                return response.getText();
            }
            return "Agent 返回为空";
        } catch (Exception e) {
            log.error("[StreamingFoodAgent] 执行异常: {}", e.getMessage(), e);
            return "处理失败: " + e.getMessage();
        }
    }
}
