package com.example.smartassistant.general.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通用对话 Agent 配置
 *
 * <p>创建纯对话 Agent（无工具），用于闲聊和通用问答的兜底处理。</p>
 *
 * <p>无工具的 ReactAgent = 普通聊天机器人，不触发 ReAct 循环，
 * 只是直接调用 ChatModel 生成回复，零额外开销。</p>
 *
 * <p>⭐ 未来扩展：如需个性化回复风格，可在 systemPrompt 中
 * 通过上下文注入用户画像参数。</p>
 */
@Configuration
@Slf4j
public class GeneralAgentConfig {

    @Value("${spring.ai.alibaba.a2a.server.card.name}")
    private String agentName;

    /**
     * 通用对话 Agent Bean
     * 纯 Chat 模式，无需工具调用，ReactAgent 仅作为 A2A 容器
     */
    @Bean
    public ReactAgent generalChatAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel) {

        log.info("[GeneralAgent] 初始化通用对话 Agent: agentName={}", agentName);

        return ReactAgent.builder()
                .name(agentName)
                .description("通用对话智能体 - 闲聊、问答、帮助、情感陪伴"
                        + "（无工具，纯 Chat 模式）")
                .model(chatModel)
                .systemPrompt("""
                        你是一个友好的通用对话助手，适用于闲聊、问答和各种日常场景。

                        行为准则：
                        - 回复简洁自然，语气友善
                        - 不知道就说不知道，不编造
                        - 敏感话题礼貌拒绝
                        - 如用户询问美食/旅行话题，引导使用相关专业服务
                        """)
                .build();
    }
}
