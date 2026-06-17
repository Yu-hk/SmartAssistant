/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.agent;

import com.example.smartassistant.common.agent.SmartReActAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Food 流式 Agent 服务
 */
@Service
@Slf4j
public class StreamingProductAgentService {

    private final SmartReActAgent productAgent;

    public StreamingProductAgentService(@Qualifier("productAgent") SmartReActAgent productAgent) {
        this.productAgent = productAgent;
    }

    /**
     * 执行美食推荐
     */
    public String execute(String userMessage) {
        try {
            log.info("[StreamingFoodAgent] 执行推理: {}", userMessage);
            String result = productAgent.execute(userMessage);
            if (result != null) {
                return result;
            }
            return "Agent 返回为空";
        } catch (Exception e) {
            log.error("[StreamingFoodAgent] 执行异常: {}", e.getMessage(), e);
            return "处理失败: " + e.getMessage();
        }
    }
}
