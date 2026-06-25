/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.metrics.AgentMetricsCollector;
import com.example.smartassistant.common.prompt.PromptBuilder;
import com.example.smartassistant.service.monitoring.OrderMetricsCollector;
import com.example.smartassistant.tools.CouponTools;
import com.example.smartassistant.tools.OrderAnalyticsTool;
import com.example.smartassistant.tools.OrderAnalyticsTool;
import com.example.smartassistant.tools.OrderKnowledgeTool;
import com.example.smartassistant.tools.OrderMemoryTool;
import com.example.smartassistant.tools.OrderTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Order Agent 配置类。
 *
 * <p>工具设计原则（遵循 Tool Context Engineering）：
 * <ul>
 *   <li>参数化查询替代 TextToSqlTool — 不让 LLM 直接写 SQL</li>
 *   <li>高风险操作（支付/退款）需二次确认</li>
 *   <li>工具描述包含适用/不适用场景</li>
 * </ul>
 */
@Configuration
@Slf4j
public class OrderAgentConfig {

    @Value("${spring.application.name}")
    private String agentName;

    @Value("classpath:prompts/order-system-prompt.txt")
    private Resource systemPromptResource;

    @Bean
    public SmartReActAgent orderAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            OrderTools orderTools,
            OrderAnalyticsTool analyticsTool,
            CouponTools couponTools,
            OrderKnowledgeTool orderKnowledgeTool,
            OrderMemoryTool orderMemoryTool,
            OrderMetricsCollector metricsCollector) {

        log.info("[OrderAgent] 初始化 Agent: agentName={}", agentName);

        List<ToolCallback> allCallbacks = new ArrayList<>();
        for (var tool : List.of(orderTools, analyticsTool, couponTools, orderKnowledgeTool, orderMemoryTool)) {
            allCallbacks.addAll(List.of(
                    MethodToolCallbackProvider.builder().toolObjects(tool).build().getToolCallbacks()));
        }

        List<ToolCallback> toolList = new ArrayList<>(allCallbacks);
        log.info("[OrderAgent] 注册 {} 个工具（订单处理 + 分析 + 优惠券 + 知识库 + 记忆）", toolList.size());

        return new SmartReActAgent(chatModel)
                .withMetrics(metricsCollector)
                .withMaxIterations(10)
                .withTimeoutMs(60_000)
                .withPreset(PromptBuilder.build()
                        .withServicePrompt(buildSystemPrompt())
                        .assemble(), toolList);
    }

    private String buildSystemPrompt() {
        try {
            return systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[OrderAgent] 系统提示词文件加载失败，使用默认提示词: {}", e.getMessage());
            return "你是一个专业的电商客服助手。根据用户需求调用工具获取信息，给出推荐。";
        }
    }
}
