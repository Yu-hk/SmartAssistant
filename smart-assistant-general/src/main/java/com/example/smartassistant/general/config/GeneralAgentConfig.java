package com.example.smartassistant.general.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.example.smartassistant.general.tool.GeneralTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 通用对话 Agent 配置
 *
 * <p>提供数学计算和单位转换工具，支持 ReAct 模式智能调用。</p>
 *
 * <p>⭐ 未来扩展：可基于用户画像调整回复风格。</p>
 */
@Configuration
@Slf4j
public class GeneralAgentConfig {

    @Value("${spring.ai.alibaba.a2a.server.card.name}")
    private String agentName;

    /**
     * 通用对话 Agent Bean
     * 注册数学计算和单位转换工具，支持 ReAct 模式
     */
    @Bean
    public ReactAgent generalChatAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            GeneralTools generalTools) {

        log.info("[GeneralAgent] 初始化通用对话 Agent: agentName={}", agentName);

        // 注册所有工具
        MethodToolCallbackProvider toolProvider = MethodToolCallbackProvider.builder()
                .toolObjects(generalTools)
                .build();
        List<ToolCallback> toolCallbacks = List.of(toolProvider.getToolCallbacks());

        log.info("[GeneralAgent] 注册 {} 个工具", toolCallbacks.size());

        return ReactAgent.builder()
                .name(agentName)
                .description("通用对话智能体 - 闲聊、问答、数学计算、单位转换")
                .model(chatModel)
                .systemPrompt(buildSystemPrompt())
                .tools(toolCallbacks.toArray(new ToolCallback[0]))
                .build();
    }

    private String buildSystemPrompt() {
        return """
                你是一个友好的通用对话助手，擅长闲聊、问答以及各类日常实用计算。

                ═══════════════════════════════════════════════════════════════
                🔧 可用工具
                ═══════════════════════════════════════════════════════════════

                1. calculate(expression) — 数学计算
                   → 适用：加减乘除、平方根、幂运算
                   → 示例："计算 3.14 * 5^2"、"sqrt(144) 等于多少"

                2. convertTemperature(value, fromUnit, toUnit) — 温度转换
                   → 单位：C(摄氏)、F(华氏)、K(开尔文)
                   → 示例："100°F 等于多少摄氏度"

                3. convertLength(value, fromUnit, toUnit) — 长度转换
                   → 单位：m/km/cm/mm/ft/in/mi
                   → 示例："5英尺是多少厘米"、"10公里等于多少英里"

                4. convertWeight(value, fromUnit, toUnit) — 重量转换
                   → 单位：kg/g/mg/lb/oz/t
                   → 示例："10磅是多少千克"

                ═══════════════════════════════════════════════════════════════
                ⚠️ 行为规范
                ═══════════════════════════════════════════════════════════════

                - 回复简洁自然，语气友善
                - 需要计算的场景先调用工具，再整合结果回复
                - 不知道就说不知道，不编造
                - 敏感话题礼貌拒绝

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
