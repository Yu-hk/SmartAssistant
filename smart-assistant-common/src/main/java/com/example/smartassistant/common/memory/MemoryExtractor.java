/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.memory;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 记忆后台提取器。
 *
 * <p>参考 Claude Code 的 forkAgent 模式：每轮对话结束后，用 LLM 自动扫描对话内容，
 * 提取用户表达的可复用偏好，写入 {@link AgentMemoryService}。</p>
 *
 * <p>提取原则：只提取用户明确表达的偏好，跳过临时对话细节和一次性信息。</p>
 */
@Component
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final AgentMemoryService memoryService;
    private final AiChatService aiChatService;

    @Autowired
    public MemoryExtractor(ObjectProvider<ChatModel> chatModelProvider,
                           AgentMemoryService memoryService,
                           AiChatService aiChatService) {
        this.chatModelProvider = chatModelProvider;
        this.memoryService = memoryService;
        this.aiChatService = aiChatService;
    }

    /** 非 Spring 构造点兼容入口。 */
    public MemoryExtractor(ObjectProvider<ChatModel> chatModelProvider, AgentMemoryService memoryService) {
        this(chatModelProvider, memoryService, AiChatService.governedDefaults());
    }

    /**
     * 从一次对话交互中提取用户偏好并保存到记忆。
     *
     * @param agent    Agent 名称（如 {@code order}）
     * @param userId   用户 ID
     * @param question 用户提问
     * @param response Agent 回复
     */
    public void extractFromConversation(String agent, String userId, String question, String response) {
        if (agent == null || userId == null || question == null || response == null) return;

        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            log.debug("[MemoryExtractor] ChatModel 未配置，跳过后台偏好提取");
            return;
        }

        String prompt = buildExtractionPrompt(agent, question, response);

        try {
            Map<String, String> preferences = aiChatService.entity(
                    chatModel,
                    prompt,
                    new ParameterizedTypeReference<>() {});
            if (preferences == null || preferences.isEmpty()) return;

            int saved = 0;
            for (Map.Entry<String, String> entry : preferences.entrySet()) {
                String key = entry.getKey().trim();
                String value = entry.getValue().trim();
                if (!key.isEmpty() && !value.isEmpty()) {
                    memoryService.save(agent, userId, key, value);
                    saved++;
                }
            }

            if (saved > 0) {
                log.info("[MemoryExtractor] 自动提取并保存 {} 条偏好: agent={}, userId={}, keys={}",
                        saved, agent, userId, preferences.keySet());
            }

        } catch (Exception e) {
            log.debug("[MemoryExtractor] 提取失败: {}", e.getMessage());
        }
    }

    /** 构建提取 prompt */
    private static String buildExtractionPrompt(String agent, String question, String response) {
        String capAgent = agent.substring(0, 1).toUpperCase() + agent.substring(1);
        return "从以下 Agent 对话中提取用户表达的、未来可能再次使用的可复用偏好。\n"
                + "只提取用户明确表达的偏好，跳过临时信息和一次性内容。\n"
                + "偏好键名使用英文驼峰，值使用中文。\n"
                + capAgent + " 常见的偏好键名参考：\n"
                + getAgentHints(agent)
                + "\n只输出一个 JSON 对象；如果未发现可提取的偏好，返回 {}。\n\n"
                + "用户：" + question + "\n"
                + capAgent + "：" + response + "\n\n"
                + "JSON：";
    }

    private static String getAgentHints(String agent) {
        return switch (agent) {
            case "order" -> """
               - preferWindowSeat: 偏好窗口座位
               - preferAisleSeat: 偏好过道座位
               - frequentRoute: 常用出行路线
               - preferPaymentMethod: 偏好支付方式""";
            case "product" -> """
               - frequentCategory: 常看品类
               - maxPrice: 价格上限
               - preferBrand: 偏好品牌""";
            case "general" -> """
               - replyStyle: 回复风格偏好
               - preferTempUnit: 偏好温度单位
               - preferCurrency: 常用币种""";
            default -> "";
        };
    }

}
