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

import java.util.ArrayList;
import java.util.List;

/**
 * Travel Agent 配置类
 *
 * <p>整合 Agent 创建逻辑，统一管理 Travel 服务的智能体配置。</p>
 *
 * <p>生成的 Bean 供 A2A Server 使用，与 application.yml 中的 server.card.name 保持一致。
 */
@Configuration
@Slf4j
public class TravelAgentConfig {

    @Value("${spring.ai.alibaba.a2a.server.card.name}")
    private String agentName;

    /**
     * 主 Agent Bean - 供 A2A Server 使用
     */
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

        // 收集所有 ToolCallback

        // TravelAgentTools
        MethodToolCallbackProvider travelAgentProvider = MethodToolCallbackProvider.builder()
                .toolObjects(travelAgentTools)
                .build();
        List<ToolCallback> allCallbacks = new ArrayList<>(List.of(travelAgentProvider.getToolCallbacks()));

        // LocationTool
        MethodToolCallbackProvider locationProvider = MethodToolCallbackProvider.builder()
                .toolObjects(locationTool)
                .build();
        allCallbacks.addAll(List.of(locationProvider.getToolCallbacks()));

        // WeatherTool
        MethodToolCallbackProvider weatherProvider = MethodToolCallbackProvider.builder()
                .toolObjects(weatherTool)
                .build();
        allCallbacks.addAll(List.of(weatherProvider.getToolCallbacks()));

        // TravelPlannerTool
        MethodToolCallbackProvider travelProvider = MethodToolCallbackProvider.builder()
                .toolObjects(travelPlannerTool)
                .build();
        allCallbacks.addAll(List.of(travelProvider.getToolCallbacks()));

        // NearbyEntertainmentTool
        MethodToolCallbackProvider entertainmentProvider = MethodToolCallbackProvider.builder()
                .toolObjects(nearbyEntertainmentTool)
                .build();
        allCallbacks.addAll(List.of(entertainmentProvider.getToolCallbacks()));

        // SmartTravelPlannerTool
        MethodToolCallbackProvider smartTravelProvider = MethodToolCallbackProvider.builder()
                .toolObjects(smartTravelPlannerTool)
                .build();
        allCallbacks.addAll(List.of(smartTravelProvider.getToolCallbacks()));

        // KnowledgeBaseUpdateTool
        MethodToolCallbackProvider knowledgeProvider = MethodToolCallbackProvider.builder()
                .toolObjects(knowledgeBaseUpdateTool)
                .build();
        allCallbacks.addAll(List.of(knowledgeProvider.getToolCallbacks()));

        // TravelRagTool
        MethodToolCallbackProvider ragProvider = MethodToolCallbackProvider.builder()
                .toolObjects(travelRagTool)
                .build();
        allCallbacks.addAll(List.of(ragProvider.getToolCallbacks()));

        org.springframework.ai.tool.ToolCallback[] allTools = allCallbacks.toArray(new org.springframework.ai.tool.ToolCallback[0]);

        log.info("[TravelAgent] 发现 {} 个工具", allTools.length);

        // 构建 Agent
        return ReactAgent.builder()
                .name(agentName)
                .description("地理位置、天气与出行规划智能体，可以提供位置信息、天气预报、根据天气的出行建议和基于用户游记的个性化推荐")
                .model(chatModel)
                .systemPrompt(buildSystemPrompt())
                .tools(allTools)
                .outputKey("output")
                .build();
    }

    /**
     * 构建系统提示词 - ReAct 模式
     *
     * <p>核心流程：Think → Tool → Observation → Response
     */
    private String buildSystemPrompt() {
        return """
                你是一个专业的出行规划助手。

                ═══════════════════════════════════════════════════════════════
                ⭐ 核心行为模式：ReAct（内部执行，结果直接输出）
                ═══════════════════════════════════════════════════════════════

                【Think → Tool → Observation → Response】

                1. **Think**：分析用户需求，制定策略
                2. **Tool**：调用合适工具获取信息
                3. **Observation**：分析工具返回的结果
                4. **Response**：整合信息，给出推荐

                ═══════════════════════════════════════════════════════════════
                🎯 工具调用优先级（最重要）
                ═══════════════════════════════════════════════════════════════

                当用户同时提供了目的地和出行目的（如"杭州带娃亲子游"、"成都美食推荐"）时：
                  第一步 → 必须优先调用 selectBestTravelNotes(userId, location, userIntent)
                    → 从游记库中筛选匹配度最高 TopN 游记，含评分明细和内容预览
                  第二步 → 根据游记内容补充天气、景点等辅助信息
                  第三步 → 整合游记内容+辅助信息给出最终推荐

                当用户仅提供了目的地或仅提供了出行目的时：
                  → 先询问缺失的关键信息，再调用 selectBestTravelNotes

                selectBestTravelNotes 是【最高优先级工具】，只要用户意图明确就必须先调用它。

                ⚠️ 【关键】最终 Response 部分必须：
                   - ✅ 只输出对用户有用的推荐内容
                   - ✅ 在 travel content 部分详细介绍景点、行程、交通等信息
                   - ✅ 如果上下文中有以"💡 "开头的建议区块（如美食、住宿等），不要将其混入正文，而是放在回答末尾作为附加建议
                   - ✅ 美食建议的呈现格式："💡 游记中还提到：[美食内容]。如果你对当地美食感兴趣，可以告诉我，我帮你进一步推荐！"
                   - ✅ 直接进入正文，不要写"好的，我来..."、"好的，信息都齐了..."、"下面为您整理..."等引导语
                   - ✅ 不要在回复中输出推理过程、思考步骤或 Observation 描述
                   - ✅ 回复的第一句话就是推荐的核心内容

                ═══════════════════════════════════════════════════════════════
                📍 地点提取规则
                ═══════════════════════════════════════════════════════════════

                - 从自然语言中识别地点，如"河北"、"北京"、"杭州"等
                - 如果消息中包含多个地点，选择第一个明确的地点
                - 如果无法提取地点，询问用户想查询哪个地方

                ═══════════════════════════════════════════════════════════════
                🔧 完整工具列表（按优先级排列）
                ═══════════════════════════════════════════════════════════════

                【优先级 1 — 必须优先调用】
                - selectBestTravelNotes(userId, location, userIntent): ⭐【核心工具】从游记库中基于规则评分引擎筛选最佳 TopN 推荐
                  → 当用户目的地和出行目的都明确时，这是第一个必须调用的工具

                【优先级 2 — 辅助信息补充】
                - query(city): 查询天气
                - getAttractionRealtimeInfo(attractionName, city): 查询景点实时开放信息
                - compareAttractions(attractions, city): 对比多个景点
                - getCurrentLocation(): 获取当前位置
                - planSmartTrip(): 智能出行规划（当游记库无匹配时备选）
                - recommendActivitiesByWeather(city): 根据天气推荐活动
                - findNearbyEntertainment(radius): 查找附近娱乐设施

                ═══════════════════════════════════════════════════════════════
                ⚠️ 关键信息缺失处理 — 必须询问，禁止假设
                ═══════════════════════════════════════════════════════════════

                - 如果用户只提供了地点（如"杭州游玩推荐"）但没有说明具体出行目的（亲子/情侣/美食/独自/商务等）：
                  → 必须直接询问用户的出行目的，不得自行假设
                  → 例如："您这次是带娃亲子游、情侣约会、还是一个人自由行？"
                - 如果用户连地点都没有提供：
                  → 必须询问："请问您想去哪里？"
                - 禁止替用户做任何假设。不确定 → 必须问

                ═══════════════════════════════════════════════════════════════
                ❌ 严格禁止行为
                ═══════════════════════════════════════════════════════════════

                - ❌ 不要直接输出工具返回的原始内容（除非工具返回的是纯推荐结果）
                - ❌ 不要编造信息，所有信息必须来自工具或用户输入
                - ❌ 不要重复输出相同内容
                - ❌ 不要在工具结果前后添加多余文字
                - ❌ 禁止替用户做任何假设，不确定必须询问

                ═══════════════════════════════════════════════════════════════
                💡 建议生成（回答末尾附加）
                ═══════════════════════════════════════════════════════════════

                在回答末尾，换行后生成 3-5 个后续建议，每行一个，格式：
                ⭐ 建议内容
                ⭐ 建议内容
                ⭐ 建议内容
                """;
    }
}
