package com.example.smartassistant.general.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 通用对话服务 - 处理闲聊和通用问答
 *
 * <p>使用 DeepSeek V4-Flash 模型，支持 reasoning_effort:max 深度推理模式。</p>
 *
 * <p>⭐ 未来扩展点：</p>
 * <ul>
 *   <li>根据用户画像调整回复风格（正式/幽默/卖萌等）</li>
 *   <li>对话上下文管理</li>
 *   <li>敏感词过滤</li>
 *   <li>多轮对话状态管理</li>
 * </ul>
 */
@Service
public class GeneralChatService {

    private static final Logger log = LoggerFactory.getLogger(GeneralChatService.class);

    private final ChatClient chatClient;

    public GeneralChatService(@Qualifier("deepSeekChatModel") ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
        log.info("[GeneralChat] 通用对话服务初始化完成");
    }

    /**
     * 处理通用对话请求
     *
     * @param userId   用户 ID（可为 null）
     * @param question 用户消息
     * @return V4-Flash 生成的回复
     */
    public String chat(String userId, String question) {
        log.info("[GeneralChat] 收到对话请求: userId={}, question={}", userId, trnucate(question));

        long startTime = System.currentTimeMillis();

        try {
            // ⭐ 未来：根据 userId 获取用户画像，动态调整 systemPrompt
            String systemPrompt = buildSystemPrompt(userId);

            String reply = chatClient.prompt()
                    .user(u -> u.text("用户问题：{question}")
                            .param("question", question))
                    .call()
                    .content();

            long duration = System.currentTimeMillis() - startTime;
            log.info("[GeneralChat] 回复完成: duration={}ms, replyLength={}", duration,
                    reply != null ? reply.length() : 0);

            return reply != null ? reply : "";

        } catch (Exception e) {
            log.error("[GeneralChat] 对话失败: {}", e.getMessage(), e);
            return "抱歉，我暂时无法回应。请稍后再试。";
        }
    }

    /**
     * 构建系统提示词
     * <p>
     * ⭐ 未来可基于 userId 从 UserProfileService 获取用户画像
     * 动态调整回复风格：
     * - "正式": 严谨、专业的回答
     * - "幽默": 轻松、有趣的语气
     * - "卖萌": 可爱、俏皮的风格
     * - "简洁": 简短、直接的回复
     */
    private String buildSystemPrompt(String userId) {
        // ⭐ 预留个性化接口
        // if (userId != null) {
        //     UserProfile profile = userProfileService.getProfile(userId);
        //     return "你是一个" + profile.getStyle() + "的对话助手...";
        // }

        return """
                你是一个友好的通用对话助手。请根据用户的问题直接回答，保持自然友善的语气。

                你可以：
                - 回答通用知识性问题
                - 进行友好的闲聊对话
                - 提供建议和帮助
                - 解释概念和原理

                注意：
                - 回复要简洁自然，不要过于啰嗦
                - 不知道的就直接说不知道，不要编造
                - 如果用户的问题涉及极端敏感内容，礼貌拒绝
                - 不要提及你是 AI 或大模型等技术细节
                """;
    }

    private String trnucate(String str) {
        if (str == null) return "";
        return str.length() > 50 ? str.substring(0, 50) + "..." : str;
    }
}
