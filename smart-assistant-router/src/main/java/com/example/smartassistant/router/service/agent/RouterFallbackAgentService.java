package com.example.smartassistant.router.service.agent;

import com.example.smartassistant.common.agent.FeedbackLog;
import com.example.smartassistant.common.agent.ReActProfileRegistry;
import com.example.smartassistant.common.agent.SmartReActAgent;
import com.example.smartassistant.common.gateway.tool.meta.DiscoverToolsHelper;
import com.example.smartassistant.common.gateway.tool.meta.DiscoverToolsTool;
import com.example.smartassistant.common.intent.WeatherQuerySupport;
import com.example.smartassistant.common.location.DeviceLocation;
import com.example.smartassistant.common.prompt.PromptBuilder;
import com.example.smartassistant.common.rag.advisor.AiChatService;
import com.example.smartassistant.common.tool.client.ToolRegistryClient;
import com.example.smartassistant.router.service.monitoring.RouterMetricsCollector;
import com.example.smartassistant.toolregistry.general.tool.GeneralTools;
import com.example.smartassistant.toolregistry.general.tool.ImageTools;
import com.example.smartassistant.toolregistry.general.tool.WeatherTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Local fallback agent used only when Router cannot assign a business agent. */
@Service
@DependsOn("generalToolCatalogRegistrar")
public class RouterFallbackAgentService {

    public static final String AGENT_NAME = "router_fallback";
    private static final Logger log = LoggerFactory.getLogger(RouterFallbackAgentService.class);

    private final SmartReActAgent agent;
    private final WeatherTool weatherTool;

    public RouterFallbackAgentService(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            WeatherTool weatherTool,
            ImageTools imageTools,
            GeneralTools generalTools,
            ToolRegistryClient toolRegistryClient,
            RouterMetricsCollector metricsCollector,
            AiChatService aiChatService,
            ObjectProvider<ReActProfileRegistry> profileProvider,
            ObjectProvider<DiscoverToolsTool> discoverToolsProvider) {
        this.weatherTool = weatherTool;
        List<ToolCallback> callbacks = toolRegistryClient.getToolCallbacks(
                "GENERAL", weatherTool, imageTools, generalTools);
        List<ToolCallback> effectiveTools = DiscoverToolsHelper.injectDiscoverTools(
                new ArrayList<>(callbacks), discoverToolsProvider.getIfAvailable());
        ChatClient chatClient = aiChatService.buildChatClient(chatModel);
        this.agent = new SmartReActAgent(chatModel)
                .withChatClient(chatClient)
                .withMetrics(metricsCollector)
                .withProfile("general", profileProvider.getIfAvailable())
                .withFeedbackLog(new FeedbackLog())
                .withPreset(PromptBuilder.build()
                        .withServicePrompt(loadPrompt())
                        .assemble(), effectiveTools);
        DiscoverToolsHelper.bindRegistrar(discoverToolsProvider.getIfAvailable(), agent);
    }

    public String execute(String question, Long userId, DeviceLocation deviceLocation) {
        if (WeatherQuerySupport.isWeatherLookup(question)) {
            String city = WeatherQuerySupport.extractCity(question);
            if (city != null) return weatherTool.queryWeather(city);
            if (deviceLocation != null && deviceLocation.isUsable()) {
                return weatherTool.queryWeather(deviceLocation.coordinateQuery());
            }
            return WeatherQuerySupport.CITY_CLARIFICATION;
        }
        String contextualQuestion = userId == null
                ? question
                : "当前用户ID：" + userId + "。\n用户问题：" + question;
        return agent.execute(contextualQuestion);
    }

    private String loadPrompt() {
        try {
            return new ClassPathResource("prompts/router-fallback-system-prompt.txt")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[RouterFallback] prompt load failed: {}", e.getMessage());
            return "你是路由服务的兜底助手。仅在业务任务无法分配时，使用已注册工具提供可靠帮助。";
        }
    }
}
