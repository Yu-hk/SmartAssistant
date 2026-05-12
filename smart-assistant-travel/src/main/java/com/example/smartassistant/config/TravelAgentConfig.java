package com.example.smartassistant.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.example.smartassistant.agent.TravelAgentTools;
import com.example.smartassistant.tools.*;
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
 * Travel Agent 配置类
 *
 * <p>系统提示词外部化在 {@code prompts/travel-system-prompt.txt}，修改无需重新编译。</p>
 */
@Configuration
@Slf4j
public class TravelAgentConfig {

    @Value("${spring.ai.alibaba.a2a.server.card.name}")
    private String agentName;

    @Value("classpath:prompts/travel-system-prompt.txt")
    private Resource systemPromptResource;

    @Bean
    public ReactAgent locationWeatherAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            LocationTool locationTool,
            WeatherTool weatherTool,
            TravelPlannerTool travelPlannerTool,
            NearbyEntertainmentTool nearbyEntertainmentTool,
            SmartTravelPlannerTool smartTravelPlannerTool,
            KnowledgeBaseUpdateTool knowledgeBaseUpdateTool,
            TravelRagTool travelRagTool,
            TravelAgentTools travelAgentTools) {

        log.info("[TravelAgent] 初始化 Agent: agentName={}", agentName);

        List<ToolCallback> allCallbacks = new ArrayList<>();
        for (var tool : List.of(travelAgentTools, locationTool, weatherTool, travelPlannerTool,
                nearbyEntertainmentTool, smartTravelPlannerTool, knowledgeBaseUpdateTool, travelRagTool)) {
            allCallbacks.addAll(List.of(
                    MethodToolCallbackProvider.builder().toolObjects(tool).build().getToolCallbacks()));
        }

        org.springframework.ai.tool.ToolCallback[] allTools = allCallbacks.toArray(new org.springframework.ai.tool.ToolCallback[0]);
        log.info("[TravelAgent] 发现 {} 个工具", allTools.length);

        return ReactAgent.builder()
                .name(agentName)
                .description("地理位置、天气与出行规划智能体，可以提供位置信息、天气预报、根据天气的出行建议和基于用户游记的个性化推荐")
                .model(chatModel)
                .systemPrompt(buildSystemPrompt())
                .tools(allTools)
                .outputKey("output")
                .build();
    }

    private String buildSystemPrompt() {
        try {
            return systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[TravelAgent] 系统提示词文件加载失败，使用默认提示词: {}", e.getMessage());
            return "你是一个专业的出行规划助手。根据用户需求调用工具获取信息，给出推荐。";
        }
    }
}
