package com.example.smartassistant.router.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.service.AgentDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Router Agent 配置
 * 使用固定路由规则，不再依赖 Nacos 动态发现
 */
@Configuration
@RefreshScope
public class RouterAgentConfig {
    
    private static final Logger log = LoggerFactory.getLogger(RouterAgentConfig.class);
    
    private final AgentDiscoveryService agentDiscoveryService;
    
    public RouterAgentConfig(AgentDiscoveryService agentDiscoveryService) {
        this.agentDiscoveryService = agentDiscoveryService;
    }
    
    @Bean
    @RefreshScope
    public ReactAgent routerAgent(ChatModel chatModel) {
        String instruction = buildRouterInstruction();
        log.info("[RouterAgent] Router Agent 初始化完成");
        
        return ReactAgent.builder()
                .name("router_agent")
                .description("智能路由 Agent - 负责意图识别和 Agent 调度")
                .model(chatModel)
                .instruction(instruction)
                .build();
    }
    
    private String buildRouterInstruction() {
        // 从 Nacos 动态获取 Agent 列表
        List<DiscoveredAgent> agents = agentDiscoveryService.discoverAllAgents();
        
        StringBuilder agentRules = new StringBuilder();
        for (DiscoveredAgent agent : agents) {
            String agentName = agent.getAgentName();
            String keywords = agent.getMetadata() != null ? agent.getMetadata().getKeywords() : "";
            String capabilities = agent.getMetadata() != null ? agent.getMetadata().getCapabilities() : "";
            agentRules.append("- ").append(agentName)
                      .append(" (关键词: ").append(keywords != null ? keywords : "无")
                      .append("; 能力: ").append(capabilities != null ? capabilities : "无")
                      .append(")").append("\n");
        }
        
        return String.format("""
                你是一个智能路由助手，负责将用户问题路由到合适的 Agent，并提取关键信息。
                
                【路由规则】
                1. 仔细分析用户问题，识别出最核心、最主要的第一个意图
                2. 根据问题中的关键词选择最匹配的 Agent
                3. 提取关键信息：地点、时间范围、意图类型等
                4. 不要添加额外解释，只返回 JSON
                
                【关键信息提取规则】
                - location: 地点名称（省份、城市、区县），如"河北"、"北京"、"杭州西湖"
                - intent: 用户意图的简要描述，如"美食推荐"、"天气查询"、"出行规划"
                - timeRange: 时间范围（如果有），如"明天"、"下周"、"周末"
                - 如果没有某个信息，该字段设为 null
                
                【输出格式】
                返回 JSON 对象，包含以下字段：
                - agentName: Agent名称
                - confidence: 置信度0.0-1.0
                - reason: 简短的路由理由
                - extractedContext: 对象，包含location、intent、timeRange、additionalParams字段
                
                【重要约束】
                - 只返回 JSON，不要输出任何其他内容
                - 不要使用 Markdown 代码块标记
                - 确保 JSON 格式正确
                - extractedContext 必须存在，即使所有字段都是 null
                - location 字段优先提取明确的地点名称
                - 置信度 confidence 应该在 0.0-1.0 之间，表示匹配的确定性
                
                【可用 Agent】
                %s
                
                【示例】
                用户问："查询北京天气"
                分析：关键词明确匹配 weather/location Agent
                返回：{"agentName":"location_weather","confidence":0.98,"reason":"用户明确询问天气","extractedContext":{"location":"北京","intent":"天气查询","timeRange":null}}
                
                用户问："帮我推荐一家川菜馆"
                分析：关键词明确匹配 food Agent
                返回：{"agentName":"food_recommendation","confidence":0.95,"reason":"用户请求美食推荐","extractedContext":{"location":null,"intent":"川菜馆推荐","timeRange":null}}
                """, agentRules.toString());
    }
}
