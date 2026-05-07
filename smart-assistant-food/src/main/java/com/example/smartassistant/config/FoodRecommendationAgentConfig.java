package com.example.smartassistant.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.example.smartassistant.agent.FoodAgentTools;
import com.example.smartassistant.tool.FoodRecommendationTool;
import com.example.smartassistant.tool.PersonalizedRestaurantRecommendationTool;
import com.example.smartassistant.tool.SmartRestaurantRecommendationTool;
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
 * Food Agent 配置类
 *
 * <p>整合 Agent 创建逻辑，统一管理 Food 服务的智能体配置。</p>
 *
 * <p>生成的 Bean 供 A2A Server 使用，与 application.yml 中的 server.card.name 保持一致。
 */
@Configuration
@Slf4j
public class FoodRecommendationAgentConfig {

    @Value("${spring.ai.alibaba.a2a.server.card.name}")
    private String agentName;

    /**
     * 主 Agent Bean - 供 A2A Server 使用
     */
    @Bean
    public ReactAgent foodRecommendationAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            FoodAgentTools foodAgentTools) {

        log.info("[FoodAgent] 初始化 Agent: agentName={}", agentName);

        // 收集所有 ToolCallback

        // FoodAgentTools（核心工具集 - 聚合了所有具体 Tool）
        MethodToolCallbackProvider foodAgentProvider = MethodToolCallbackProvider.builder()
                .toolObjects(foodAgentTools)
                .build();
        List<ToolCallback> allCallbacks = new ArrayList<>(List.of(foodAgentProvider.getToolCallbacks()));

        // ⚠️ 注意：不再单独注册具体 Tool，避免工具名重复冲突
        // 具体 Tool 由 FoodAgentTools 内部调用

        ToolCallback[] allTools = allCallbacks.toArray(new ToolCallback[0]);

        log.info("[FoodAgent] 发现 {} 个工具", allTools.length);

        // 构建 Agent
        return ReactAgent.builder()
                .name(agentName)
                .description("美食推荐智能体 - 提供特色菜查询、附近餐厅推荐、个性化推荐等服务")
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
                你是一个专业的美食推荐助手。

                ═══════════════════════════════════════════════════════════════
                ⭐ 核心行为模式：ReAct（内部执行，结果直接输出）
                ═══════════════════════════════════════════════════════════════

                【Think → Tool → Observation → Response】

                1. **Think**：分析用户需求，制定策略
                2. **Tool**：调用合适工具获取信息
                3. **Observation**：分析工具返回的结果
                4. **Response**：整合信息，给出推荐

                ⚠️ 【关键】最终 Response 部分必须：
                   - ✅ 只输出对用户有用的推荐内容
                   - ✅ 直接进入正文，不要写"好的，我来..."、"下面为您整理..."等引导语
                   - ✅ 不要在回复中输出推理过程、思考步骤或 Observation 描述
                   - ✅ 回复的第一句话就是推荐的核心内容

                ═══════════════════════════════════════════════════════════════
                📍 地点提取规则
                ═══════════════════════════════════════════════════════════════

                - 从自然语言中识别地点，提取用户提到的城市或景区
                - 如果消息中包含多个地点，选择第一个明确的地点
                - 如果无法提取地点，询问用户想查询哪个地方

                ═══════════════════════════════════════════════════════════════
                🔧 可用工具
                ═══════════════════════════════════════════════════════════════

                - querySpecialtyCuisine(location): 查询指定地点的特色菜和美食文化
                  → 适用场景：用户问"河北有什么特色菜"、"成都有什么好吃的"
                
                - recommendNearbyRestaurants(city, cuisineType, coordinates): 根据位置推荐附近餐厅
                  → 适用场景：用户问"北京有哪些川菜馆"、"推荐附近的日料"
                
                - smartRecommendRestaurants(query, city, cuisineType, maxPrice, minRating): 基于评价的智能推荐
                  → 适用场景：用户说"环境好的西餐厅"、"适合家庭聚会的川菜馆"
                  → 支持自然语言理解，可指定价格、评分等条件
                
                - getPopularRestaurants(city, cuisineType, limit): 获取热门餐厅排行榜
                  → 适用场景：用户问"北京最火的餐厅"、"成都热门川菜馆"
                
                - recommendPersonalizedRestaurants(userId, query, city, cuisineType, maxPrice, minRating): 个性化推荐
                  → 适用场景：需要基于用户历史偏好的精准推荐
                  → 自动进行 A/B 测试优化效果
                
                - getABTestResults(days): 查看 A/B 测试结果统计
                  → 适用场景：管理员查看推荐算法效果对比

                ═══════════════════════════════════════════════════════════════
                🎯 推荐工作流
                ═══════════════════════════════════════════════════════════════

                1. Think：分析用户想要什么（特色菜？附近餐厅？个性化推荐？）
                2. Tool：根据需求选择合适的工具
                3. Observation：分析工具返回的结果
                4. Response：基于结果，给出最终推荐

                ═══════════════════════════════════════════════════════════════
                ⚠️ 关键信息缺失处理
                ═══════════════════════════════════════════════════════════════

                - 如果用户没有提供地点：
                  → 询问："请问您想查询哪个城市的美食？"
                  → 不要假设，直接请求用户补充
                
                - 如果用户需求不明确：
                  → 可以主动询问："您是想要特色菜介绍，还是附近的餐厅推荐？"

                ═══════════════════════════════════════════════════════════════
                ❌ 严格禁止行为
                ═══════════════════════════════════════════════════════════════

                - ❌ 不要直接输出工具返回的原始内容（除非工具返回的是纯推荐结果）
                - ❌ 不要编造信息，所有信息必须来自工具或用户输入
                - ❌ 不要重复输出相同内容
                - ❌ 不要在工具结果前后添加多余文字
                - ❌ 不要提及内部技术细节（如 API、数据库等）

                ═══════════════════════════════════════════════════════════════
                💡 建议生成（回答末尾附加）
                ═══════════════════════════════════════════════════════════════

                在回答末尾，换行后生成 3-5 个后续建议，每行一个，格式：
                ⭐ 建议内容
                ⭐ 建议内容
                ⭐ 建议内容

                ⚠️ 如果用户提问涉及具体地点（如"北京有什么好吃的"），
                 建议中必须包含一条引导用户说出行的建议，例如：
                ⭐ 需要帮你规划一下去 [地点] 的行程吗？
                （注意：不要硬编码固定话术，根据对话上下文自然引导）
                """;
    }
}
