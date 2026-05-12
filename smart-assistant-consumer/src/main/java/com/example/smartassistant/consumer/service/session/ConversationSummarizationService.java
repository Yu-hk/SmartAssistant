/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 对话叙事化摘要服务
 * 使用 LLM 将原始对话内容转换为简洁的第三人称叙事总结，
 * 提取事实信息（偏好、决策、选择项），去除对话填充语。
 */
@Service
public class ConversationSummarizationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummarizationService.class);

    /**
     * 内容最大长度（字符数）
     * 超过此长度的内容将被安全截断到句子边界
     */
    private static final int MAX_CONTENT_LENGTH = 8000;

    private final ChatClient chatClient;

    public ConversationSummarizationService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 将原始对话文本转换为叙事总结
     * <p>
     * 处理逻辑：
     * 1. 原始内容被 LLM 摘要完全替代（清空原记忆）
     * 2. 如果原始内容超长被截断，截断部分不经过 LLM，直接原文追加到摘要后面
     * 3. LLM 调用失败时降级到原始内容（fail-safe）
     *
     * @param rawContent 原始对话内容（"用户消息\n助手回复" 格式）
     * @return 叙事总结文本（+截断部分的原文）；如果 LLM 调用失败，返回原始内容（fail-safe）
     */
    public String summarize(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return "";
        }

        // ★ 安全截断超长内容，同时保留被截断的部分
        TruncationResult truncationResult = safeTruncateWithRemainder(rawContent);
        String processedContent = truncationResult.content();
        String remainder = truncationResult.remainder();
        boolean isTruncated = !remainder.isBlank();

        if (isTruncated) {
            log.info("[ConvNarrative] 内容已安全截断: {} -> {} (摘要部分) + {} (截断部分) 字符",
                    rawContent.length(), processedContent.length(), remainder.length());
        }

        try {
            String prompt = buildNarrativePrompt(processedContent, isTruncated);
            String narrative = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (narrative == null || narrative.isBlank()) {
                log.warn("[ConvNarrative] LLM 返回空结果，降级到原始内容");
                return rawContent;
            }

            // ★ 核心逻辑：用摘要替代原始内容（清空原记忆），截断部分直接原文追加
            String result = narrative.trim();
            if (isTruncated) {
                result = result + "\n\n---\n\n" + remainder;
                log.info("[ConvNarrative] 截断部分已直接追加到摘要后: {} 字符", remainder.length());
            }

            log.debug("[ConvNarrative] 叙事总结完成: 原始={}字符, 最终={}字符",
                    processedContent.length(), result.length());
            return result;

        } catch (Exception e) {
            log.warn("[ConvNarrative] LLM 叙事总结失败，降级到原始内容: {}", e.getMessage());
            return rawContent;
        }
    }

    /**
     * 安全截断结果记录
     *
     * @param content   截断后的前半部分（用于 LLM 摘要）
     * @param remainder 被截断的后半部分（直接原文追加）
     */
    private record TruncationResult(String content, String remainder) {}

    /**
     * 安全截断内容到最大长度，同时保留被截断的部分
     * <p>
     * 截断策略：
     * 1. 如果内容未超过最大长度，remaining 为空
     * 2. 如果超过，在最后一个完整句子边界处截断
     * 3. 句子边界包括：中文标点（。！？）和英文标点（.!?）
     * 4. 如果没有找到合适边界，退回到在空格处截断
     * 5. 最后手段：直接在最大长度处截断（保留最后几个字符的安全边界）
     * <p>
     * 被截断的部分不会丢失，而是通过 remainder 返回，直接追加到摘要后面。
     *
     * @param content 原始内容
     * @return 截断结果（截断部分 + 剩余部分）
     */
    private TruncationResult safeTruncateWithRemainder(String content) {
        if (content == null || content.length() <= MAX_CONTENT_LENGTH) {
            return new TruncationResult(content, "");
        }

        log.info("[ConvNarrative] 内容超长，开始安全截断: {} > {} 字符",
                content.length(), MAX_CONTENT_LENGTH);

        // 在最大长度范围内查找最后一个句子边界
        int truncateAt = findLastSentenceBoundary(content, MAX_CONTENT_LENGTH);

        // 如果没找到合适边界，尝试在空格处截断
        if (truncateAt == -1) {
            truncateAt = findLastSpaceBeforeLimit(content, MAX_CONTENT_LENGTH);
        }

        // 最后手段：直接在最大长度处截断
        if (truncateAt == -1) {
            truncateAt = MAX_CONTENT_LENGTH;
        }

        String truncated = content.substring(0, truncateAt).trim();
        String remainder = content.substring(truncateAt).trim();
        return new TruncationResult(truncated, remainder);
    }

    /**
     * 在指定限制前查找最后一个句子边界
     * <p>
     * 句子边界字符：. ! ? 。 ！ ？
     * 特殊处理：避免截断缩写（如 Mr. Mrs. Dr. 等）
     *
     * @param content 内容
     * @param limit   最大长度限制
     * @return 截断位置（exclusive），如果没找到返回 -1
     */
    private int findLastSentenceBoundary(String content, int limit) {
        int searchEnd = Math.min(limit, content.length());
        int lastBoundary = -1;

        for (int i = 0; i < searchEnd; i++) {
            char c = content.charAt(i);

            // 检查句子结束标点
            if (c == '。' || c == '！' || c == '？' ||
                    c == '.' || c == '!' || c == '?') {

                // 英文句点需要特殊处理，避免截断缩写
                if (c == '.') {
                    // 检查是否是缩写（如 Mr. Mrs. Dr. etc.）
                    if (isAbbreviation(content, i)) {
                        continue; // 跳过这个句点
                    }
                }

                // 找到句子边界，记录位置（包含这个标点）
                lastBoundary = i + 1;
            }
        }

        return lastBoundary;
    }

    /**
     * 判断指定位置的句点是否是缩写的一部分
     *
     * @param content 内容
     * @param dotIndex 句点位置
     * @return 如果是缩写返回 true
     */
    private boolean isAbbreviation(String content, int dotIndex) {
        // 简单启发式规则：
        // 1. 句点前是小写字母，且后面紧跟大写字母或空格+大写字母，可能是缩写
        // 2. 常见缩写：Mr. Mrs. Dr. Ms. Prof. etc.

        if (dotIndex == 0 || dotIndex >= content.length() - 1) {
            return false;
        }

        char before = content.charAt(dotIndex - 1);
        char after = content.charAt(dotIndex + 1);

        // 如果句点前是小写字母，且后面是大写字母，可能是缩写（如 "Mr.Smith"）
        if (Character.isLowerCase(before) && Character.isUpperCase(after)) {
            return true;
        }

        // 如果句点前是字母，且后面是空格+小写字母，可能是缩写（如 "etc. and"）
        if (Character.isLetter(before) && after == ' ') {
            // 检查空格后的字符
            if (dotIndex + 2 < content.length()) {
                char afterSpace = content.charAt(dotIndex + 2);
                if (Character.isLowerCase(afterSpace)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 在指定限制前查找最后一个空格
     *
     * @param content 内容
     * @param limit   最大长度限制
     * @return 截断位置（在空格之后），如果没找到返回 -1
     */
    private int findLastSpaceBeforeLimit(String content, int limit) {
        int searchEnd = Math.min(limit, content.length());

        for (int i = searchEnd - 1; i >= 0; i--) {
            if (Character.isWhitespace(content.charAt(i))) {
                return i + 1; // 在空格之后截断
            }
        }

        return -1;
    }

    /**
     * 构建叙事总结 Prompt
     * <p>
     * 设计原则：
     * - 提取事实信息（偏好、决策、选择的项目）
     * - 去除对话填充语（问候语、过渡句、客套话等）
     * - 以客观的第三人称书写
     * - 保持信息完整性
     * - 控制长度在 100-300 字
     *
     * @param content     对话内容（已被截断）
     * @param isTruncated 内容是否被截断（截断部分将直接追加到摘要输出末尾）
     */
    private String buildNarrativePrompt(String content, boolean isTruncated) {
        StringBuilder promptBuilder = new StringBuilder();

        promptBuilder.append("""
                你是一个专业的对话记录员。请将以下用户与 AI 助手的对话内容，
                改写成一段简洁的第三人称叙事总结。
                
                【处理规则】
                1. 提取所有事实性信息：用户的偏好、决定、查询目标、选择项
                2. 提取系统推荐的关键内容：推荐的店铺、地点、选项及理由
                3. 去除对话填充语：问候语（"你好"、"嗨"）、过渡句（"请问"、"让我看看"）、
                   客套话（"希望这个回答对你有帮助"、"如果还有其他问题"）、语气词
                4. 以客观第三人称书写：使用"用户查询了..."、"用户表示..."、"系统推荐了..."、
                   "系统提供了..."等句式
                5. 保持信息完整性：不要遗漏重要的备选方案、价格信息、地址等关键数据
                6. 保持语言风格简洁，按时间顺序叙述
                """);

        // 如果内容被截断，添加提示
        if (isTruncated) {
            promptBuilder.append("""
                
                【重要提示】
                由于对话内容较长，以下内容已被安全截断（在完整句子处截断）。
                请基于提供的部分内容进行总结，不要提及内容被截断的事实。
                如果在截断处有明显的未完成任务，可以在总结中提及"用户还在继续..."。
                """);
        }

        promptBuilder.append("""
                
                【对话内容】
                """);

        promptBuilder.append(content);

        promptBuilder.append("""
                
                【输出要求】
                请直接输出叙事总结文本，不要包含任何额外说明、不要使用 Markdown 格式。
                输出控制在一段内，通常在 100-300 字之间。
                """);

        return promptBuilder.toString();
    }
}
