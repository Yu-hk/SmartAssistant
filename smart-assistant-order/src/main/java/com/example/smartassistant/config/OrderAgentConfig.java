/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.config;

import com.example.smartassistant.common.agent.FeedbackLog;
import com.example.smartassistant.common.agent.ReActProfileRegistry;
import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.gateway.tool.meta.DiscoverToolsHelper;
import com.example.smartassistant.common.gateway.tool.meta.DiscoverToolsTool;
import com.example.smartassistant.common.prompt.PromptBuilder;
import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.order.tool.CouponTools;
import com.example.smartassistant.order.tool.OrderAnalyticsTool;
import com.example.smartassistant.order.tool.OrderKnowledgeTool;
import com.example.smartassistant.order.tool.OrderMemoryTool;
import com.example.smartassistant.order.tool.OrderTools;
import com.example.smartassistant.order.tool.TextToSqlTool;
import com.example.smartassistant.common.rag.trace.StageTraceRecorder;
import com.example.smartassistant.service.monitoring.OrderMetricsCollector;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
@ComponentScan(basePackages = {
    "com.example.smartassistant"
})
@Slf4j
public class OrderAgentConfig {

    @Value("${spring.application.name}")
    private String agentName;

	@Value("classpath:prompts/order-system-prompt.txt")
	private Resource systemPromptResource;

	/**
	 * T2d：发现元工具（可选注入，由特性开关 {@code tool-registry.t2-mcp-discovery-enabled} 控制）。
	 * 为 null 时不注入 discover_tools。
	 */
	@Autowired(required = false)
	private DiscoverToolsTool discoverToolsTool;

    @Bean
    public SmartReActAgent orderAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            OrderTools orderTools,
            OrderMemoryTool orderMemoryTool,
            OrderAnalyticsTool orderAnalyticsTool,
            OrderKnowledgeTool orderKnowledgeTool,
            TextToSqlTool textToSqlTool,
            CouponTools couponTools,
            OrderMetricsCollector metricsCollector,
            ObservationRegistry observationRegistry,
            AiChatService aiChatService,
            @Autowired(required = false) ReActProfileRegistry reactProfileRegistry) {

        log.info("[OrderAgent] 初始化 Agent: agentName={}", agentName);

		// 从本模块 @Component 工具 Bean 直接扫描加载
		ToolCallback[] moduleToolCallbacks = MethodToolCallbackProvider.builder()
				.toolObjects(orderTools, orderMemoryTool, orderAnalyticsTool, orderKnowledgeTool, textToSqlTool, couponTools)
				.build()
				.getToolCallbacks();
		List<ToolCallback> toolList = new ArrayList<>(Arrays.asList(moduleToolCallbacks));

		log.info("[OrderAgent] 加载 {} 个本模块工具", toolList.size());

		// ⭐ T2d：注入 discover_tools 元工具 + 绑定注册器
		List<ToolCallback> effectiveToolList = DiscoverToolsHelper.injectDiscoverTools(toolList, discoverToolsTool);

		// ⭐ 构建 ChatClient
		ChatClient chatClient = aiChatService.buildChatClient(chatModel);
		log.info("[OrderAgent] ChatClient 由 AiChatService 统一装配 Advisor 链");

		SmartReActAgent agent = new SmartReActAgent(chatModel)
				.withChatClient(chatClient)
				.withMetrics(metricsCollector)
				.withProfile("order", reactProfileRegistry)
				.withObservationRegistry(observationRegistry)
				.withFeedbackLog(new FeedbackLog())
				.withPreset(PromptBuilder.build()
						.withServicePrompt(buildSystemPrompt())
						.assemble(), effectiveToolList);

		// ⭐ T2d：Agent 创建后设置注册器
		DiscoverToolsHelper.bindRegistrar(discoverToolsTool, agent);

        return agent;
    }

    private String buildSystemPrompt() {
        try {
            return systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[OrderAgent] 系统提示词文件加载失败，使用默认提示词: {}", e.getMessage());
            return "你是一个专业的电商客服助手。根据用户需求调用工具获取信息，给出推荐。";
        }
    }

    /**
     * ⭐ P1 全阶段 trace 记录器（Redis 可用时持久化到 a2a:stage:trace:{requestId}，否则纯内存）。
     */
    @Bean
    public StageTraceRecorder stageTraceRecorder(
            @Autowired(required = false) org.springframework.data.redis.core.StringRedisTemplate redisTemplate) {
        return new StageTraceRecorder(redisTemplate);
    }
}
