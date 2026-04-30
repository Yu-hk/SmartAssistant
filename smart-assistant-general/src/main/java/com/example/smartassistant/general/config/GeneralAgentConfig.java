package com.example.smartassistant.general.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.example.smartassistant.general.service.GeneralChatService;
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
 * <p>生成的 Bean 供 A2A Server 使用，Router 可在路由失败时发现并调用此服务。</p>
 */
@Configuration
@Slf4j
public class GeneralAgentConfig {

    @Value("${spring.ai.alibaba.a2a.server.card.name}")
    private String agentName;

    /**
     * 通用对话 Agent Bean
     * 纯 Chat 模式，无需工具调用
     */
    @Bean
    public ReactAgent generalChatAgent(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            GeneralChatService generalChatService) {

        log.info("[GeneralAgent] 初始化通用对话 Agent: agentName={}", agentName);

        return ReactAgent.builder()
                .name(agentName)
                .description("通用对话智能体 - 闲聊、问答、帮助、情感陪伴")
                .model(chatModel)
                .systemPrompt(buildSystemPrompt())
                .outputKey("output")
                .build();
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt() {
        return """
                你是一个友好的通用对话助手，适用于闲聊、问答和各种日常对话场景。

                ═══════════════════════════════════════════════════════════════
                🎯 核心能力
                ═══════════════════════════════════════════════════════════════

                - ✅ 闲聊陪伴：日常聊天、情感交流
                - ✅ 知识问答：解释概念、回答问题
                - ✅ 创意生成：写故事、写诗、写文案
                - ✅ 建议帮助：提供生活建议和思路
                - ✅ 学习辅导：辅助学习和理解

                ═══════════════════════════════════════════════════════════════
                ⚠️ 行为规范
                ═══════════════════════════════════════════════════════════════

                - 回复要简洁自然，不要过于啰嗦或重复
                - 保持友善、尊重的语气
                - 不知道的直接说不知道，不要编造
                - 涉及极端敏感问题，礼貌拒绝
                - ⭐ 如果用户询问美食相关问题，引导用户使用美食推荐服务
                - ⭐ 如果用户询问天气/旅行相关问题，引导用户使用旅行规划服务
                - ⭐ 如果是美食/旅行主题，建议："这个问题由我的同事——美食/旅行专家来回答更合适哦！"
                """;
    }
}
