/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.rag;

import com.example.smartassistant.common.prompt.PromptManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final PromptManager promptManager;

    @Value("${router.agent.rag.enabled:false}")
    private boolean ragEnabled;

    @Value("${router.agent.rag.max-history-messages:10}")
    private int maxHistoryMessages;

    @Value("${router.agent.rag.summary-max-tokens:500}")
    private int summaryMaxTokens;

    @Value("${router.agent.rag.backref-enabled:true}")
    private boolean backrefEnabled;

    public RouterRagService(ChatClient.Builder chatClientBuilder, PromptManager promptManager) {
        this.chatClient = chatClientBuilder.build();
        this.promptManager = promptManager;
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

            // ⭐ Step 2.5: 提取实体并生成回指文本
            String backReferences = "";
            if (backrefEnabled) {
                List<EntityRef> entities = extractEntities(conversationHistory);
                if (!entities.isEmpty()) {
                    backReferences = generateBackReferences(entities);
                    log.debug("[RouterRAG] 回指: {} 个实体", entities.size());
                }
            }

            // Step 3: 拼接增强后的问题（含回指）
            String enhancedQuestion = buildEnhancedQuestion(question, summary, backReferences);

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

        // 问题中包含代词或上下文依赖词（如"它"、"这个"、"上次"等）需要 RAG。
        // 注意：上下文依赖词本身往往很短（"它"、"还有呢"等），因此必须先于长度守卫判定，
        // 否则长度阈值会先短路这些本应触发 RAG 的短问题，使上下文检测形同虚设。
        String[] contextIndicators = {"它", "这个", "那", "上次", "之前", "继续", "还有", "另外"};
        for (String indicator : contextIndicators) {
            if (question.contains(indicator)) {
                return true;
            }
        }

        // 问题过短（简单查询）且不含上下文依赖词，不需要 RAG
        if (question.length() < 10) {
            return false;
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

        // P2 Prompt 外部化：prompts/router/rag-summary.txt
        String summaryPrompt = promptManager.ragSummary() + "\n\n"
                + "对话历史：\n" + historyText + "\n\n"
                + "当前问题：" + currentQuestion + "\n\n"
                + "要求：\n1. 只提取与当前问题相关的历史信息\n"
                + "2. 保持简洁，不超过 " + summaryMaxTokens + " 字\n"
                + "3. 直接输出摘要，不要解释\n\n相关上下文：";

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
     * 构建增强后的问题（包含上下文摘要和实体回指）。
     */
    private String buildEnhancedQuestion(String question, String contextSummary, String backReferences) {
        StringBuilder sb = new StringBuilder();

        if (contextSummary != null && !contextSummary.isBlank()) {
            sb.append("【上下文】\n").append(contextSummary.trim()).append("\n\n");
        }

        if (backReferences != null && !backReferences.isBlank()) {
            sb.append("【历史引用】\n").append(backReferences.trim()).append("\n\n");
        }

        sb.append("【当前问题】\n").append(question);

        if (sb.length() == question.length() + "【当前问题】\n".length()) {
            return question; // 无增强，返回原问题
        }
        return sb.toString();
    }

    /**
     * ⭐ 从对话历史中提取关键实体。
     * <p>
     * 使用 LLM 识别用户和 Agent 消息中提及的实体，
     * 包括：订单号、商品名、金额、日期、操作动作、决策结果等。
     * </p>
     *
     * @param conversationHistory 历史对话
     * @return 实体引用列表
     */
    private List<EntityRef> extractEntities(List<String> conversationHistory) {
        StringBuilder historyBuilder = new StringBuilder();
        for (int i = 0; i < Math.min(conversationHistory.size(), maxHistoryMessages); i++) {
            historyBuilder.append(conversationHistory.get(i)).append("\n");
        }
        String historyText = historyBuilder.toString();

        // P2 Prompt 外部化：prompts/router/rag-entity.txt
        String entityPrompt = promptManager.ragEntityExtraction() + "\n\n"
                + "对话历史：\n" + historyText;

        try {
            String response = chatClient.prompt()
                    .user(entityPrompt)
                    .call()
                    .content();

            if (response == null || response.isBlank()) return List.of();

            List<EntityRef> entities = new ArrayList<>();
            for (String line : response.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\|", 3);
                if (parts.length >= 2) {
                    entities.add(new EntityRef(
                            parts[0].trim(),
                            parts[1].trim(),
                            parts.length > 2 ? parts[2].trim() : ""));
                }
            }
            return entities;
        } catch (Exception e) {
            log.debug("[RouterRAG] 实体提取失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * ⭐ 基于提取的实体生成回指文本。
     * <p>
     * 将实体按类型分组，生成结构化引用，帮助 LLM 快速定位上下文：
     * <pre>
     * - 订单: ORD-001 (第1轮) → 当前处于"已支付"状态
     * - 操作: 查询订单 (第2轮) → 已由 order_agent 处理
     * - 商品: iPhone 15 (第1轮) → 用户关注的商品
     * </pre>
     * </p>
     */
    private String generateBackReferences(List<EntityRef> entities) {
        // 按类型分组
        Map<String, List<EntityRef>> byType = new LinkedHashMap<>();
        for (EntityRef e : entities) {
            byType.computeIfAbsent(e.type, k -> new ArrayList<>()).add(e);
        }

        StringBuilder sb = new StringBuilder();
        // 优先输出：决策 → 操作 → 实体
        String[] priorityTypes = {"DECISION", "ACTION", "ORDER_ID", "PRODUCT", "AMOUNT", "DATE"};
        Set<String> rendered = new HashSet<>();

        for (String type : priorityTypes) {
            List<EntityRef> refs = byType.get(type);
            if (refs == null || refs.isEmpty()) continue;

            // 去重（同类型同值只取首次）
            Set<String> seen = new HashSet<>();
            List<String> uniqueValues = new ArrayList<>();
            for (EntityRef ref : refs) {
                if (seen.add(ref.value)) {
                    uniqueValues.add(ref.value);
                }
            }
            rendered.add(type);
            sb.append("- ").append(typeLabel(type)).append(": ")
                    .append(String.join(", ", uniqueValues)).append("\n");
        }

        // 其他未渲染类型
        for (Map.Entry<String, List<EntityRef>> entry : byType.entrySet()) {
            if (rendered.contains(entry.getKey())) continue;
            Set<String> seen = new HashSet<>();
            List<String> uniqueValues = new ArrayList<>();
            for (EntityRef ref : entry.getValue()) {
                if (seen.add(ref.value)) uniqueValues.add(ref.value);
            }
            sb.append("- ").append(typeLabel(entry.getKey())).append(": ")
                    .append(String.join(", ", uniqueValues)).append("\n");
        }

        return sb.toString();
    }

    /** 实体类型中文标签 */
    private static String typeLabel(String type) {
        return switch (type) {
            case "ORDER_ID" -> "订单号";
            case "PRODUCT" -> "商品";
            case "AMOUNT" -> "金额";
            case "DATE" -> "日期";
            case "ACTION" -> "用户操作";
            case "DECISION" -> "系统决策";
            default -> type;
        };
    }

    /**
     * ⭐ 实体引用——从对话历史中提取的关键实体。
     */
    record EntityRef(String type, String value, String sourceIndex) {}

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
