package com.example.smartassistant.router.service.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * ⭐ Router RAG 服务
 * 利用历史对话上下文增强当前问题，提升路由准确性
 * 实现策略：
 * 1. 从 Redis 获取会话历史（已有 loadConversationHistoryFromRedis）
 * 2. 使用 LLM 对历史进行摘要，提取与当前问题相关的信息
 * 3. 将摘要作为上下文拼接到问题中
 */
@Service
public class RouterRagService {

    private static final Logger log = LoggerFactory.getLogger(RouterRagService.class);

    private final ChatClient chatClient;

    @Value("${router.agent.rag.enabled:false}")
    private boolean ragEnabled;

    @Value("${router.agent.rag.max-history-messages:10}")
    private int maxHistoryMessages;

    @Value("${router.agent.rag.summary-max-tokens:500}")
    private int summaryMaxTokens;

    public RouterRagService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * ⭐ RAG 增强：利用历史对话上下文增强当前问题
     *
     * @param question 当前问题
     * @param conversationHistory 历史对话（从 Redis 加载）
     * @return 增强后的问题
     */
    public String enhanceQuestion(String question, List<String> conversationHistory) {
        // 检查是否启用 RAG
        if (!ragEnabled) {
            log.debug("[RouterRAG] RAG 未启用，返回原始问题");
            return question;
        }

        // 检查是否有历史记录
        if (conversationHistory == null || conversationHistory.isEmpty()) {
            log.debug("[RouterRAG] 无历史记录，返回原始问题: {}", truncate(question));
            return question;
        }

        try {
            // Step 1: 判断当前问题是否需要 RAG 增强
            if (!isRagWorthy(question, conversationHistory)) {
                log.debug("[RouterRAG] 当前问题无需 RAG 增强");
                return question;
            }

            // Step 2: 构建摘要
            String summary = generateContextSummary(question, conversationHistory);

            // Step 3: 拼接增强后的问题
            String enhancedQuestion = buildEnhancedQuestion(question, summary);

            log.info("[RouterRAG] RAG 增强完成: 原始问题={} 字符, 增强后={} 字符, 摘要={} 字符",
                    question.length(), enhancedQuestion.length(), summary.length());

            return enhancedQuestion;

        } catch (Exception e) {
            log.warn("[RouterRAG] RAG 增强失败，使用原始问题: {}", e.getMessage());
            return question;
        }
    }

    /**
     * 判断当前问题是否需要 RAG 增强
     */
    private boolean isRagWorthy(String question, List<String> conversationHistory) {
        // 简单问题或首次提问不需要 RAG
        if (conversationHistory.size() <= 1) {
            return false;
        }

        // 问题过短（简单查询）不需要 RAG
        if (question.length() < 10) {
            return false;
        }

        // 问题中包含代词或上下文依赖词（如"它"、"这个"、"上次"等）需要 RAG
        String[] contextIndicators = {"它", "这个", "那", "上次", "之前", "继续", "还有", "另外"};
        for (String indicator : contextIndicators) {
            if (question.contains(indicator)) {
                return true;
            }
        }

        // 问题很泛（只有几个字）需要 RAG
        return question.length() < 15 && conversationHistory.size() > 3;
    }

    /**
     * 生成上下文摘要
     */
    private String generateContextSummary(String currentQuestion, List<String> conversationHistory) {
        // 拼接历史对话
        StringBuilder historyBuilder = new StringBuilder();
        for (int i = 0; i < Math.min(conversationHistory.size(), maxHistoryMessages); i++) {
            historyBuilder.append(conversationHistory.get(i)).append("\n");
        }

        String historyText = historyBuilder.toString();

        // 构建摘要 Prompt
        String summaryPrompt = String.format("""
                你是一个对话摘要助手。根据以下对话历史和当前问题，提取与当前问题最相关的上下文信息。

                对话历史：
                %s

                当前问题：%s

                要求：
                1. 只提取与当前问题相关的历史信息
                2. 保持简洁，不超过 %d 字
                3. 直接输出摘要，不要解释

                相关上下文：
                """, historyText, currentQuestion, summaryMaxTokens);

        // 调用 LLM 生成摘要
        String summary = chatClient.prompt()
                .user(summaryPrompt)
                .call()
                .content();

        if (summary == null || summary.isBlank()) {
            log.warn("[RouterRAG] LLM 摘要为空");
            return "";
        }

        // 清理摘要
        summary = summary.trim();
        log.debug("[RouterRAG] 生成摘要: {} 字符", summary.length());

        return summary;
    }

    /**
     * 构建增强后的问题
     */
    private String buildEnhancedQuestion(String question, String contextSummary) {
        if (contextSummary == null || contextSummary.isBlank()) {
            return question;
        }

        return String.format("""
                【上下文】
                %s

                【当前问题】
                %s
                """, contextSummary.trim(), question);
    }

    /**
     * ⭐ 截断字符串（用于日志）
     */
    private String truncate(String str) {
        if (str == null) return "";
        return str.length() > 50 ? str.substring(0, 50) + "..." : str;
    }

    /**
     * 检查 RAG 是否启用
     */
    public boolean isEnabled() {
        return ragEnabled;
    }

    /**
     * 获取配置信息（用于调试）
     */
    public Map<String, Object> getConfig() {
        return Map.of(
                "enabled", ragEnabled,
                "maxHistoryMessages", maxHistoryMessages,
                "summaryMaxTokens", summaryMaxTokens
        );
    }
}
