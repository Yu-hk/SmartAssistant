/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.agent;

import com.example.smartassistant.common.memory.ConversationSummaryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史压缩器。
 * <p>
 * 从 SmartReActAgent 拆出，负责增量/滚动式对话历史压缩。
 * </p>
 *
 * @author Yu-hk
 * @since 2026-07-13
 */
public class ContextCompressor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompressor.class);

    private static final String SUMMARY_PROMPT = """
            请把下面的对话历史压缩成结构化摘要。

            必须包含以下 9 个部分：
            1. 用户的核心诉求与初始目标
            2. 关键技术概念与业务上下文
            3. 涉及的文件、服务或数据
            4. 错误与修复记录
            5. 问题解决过程（哪些成功了，哪些失败了）
            6. 用户的关键反馈与偏好
            7. 已完成的待办事项
            8. 仍未解决的问题或待办
            9. 后续建议与下一步计划

            要求：
            - 按以上 9 个部分编号输出，每个部分 1-3 句
            - 保留所有重要的技术细节、错误信息和用户反馈
            - 不要遗漏已完成的工具调用及其核心结果
            - 如果某部分无内容，标注「无」
            不要复述每条原文，不要保留无关闲聊。
            """;

    private static final int MAX_SUMMARY_INPUT_CHARS = 6_000;
    private static final int COMPRESS_MIN_SIZE = 6;

    private final ChatModel chatModel;
    private final ReActProfile profile;
    private final ConversationSummaryStore summaryStore;
    private final List<String> summaryChain;
    private int summaryGeneration = 0;

    public ContextCompressor(ChatModel chatModel, ReActProfile profile,
                             ConversationSummaryStore summaryStore, List<String> summaryChain) {
        this.chatModel = chatModel;
        this.profile = profile;
        this.summaryStore = summaryStore;
        this.summaryChain = summaryChain;
    }

    public int getSummaryGeneration() { return summaryGeneration; }

    /**
     * 压缩对话历史（带 MicroCompact 工具结果清理 + 增量更新）。
     *
     * @param messages 当前消息列表
     * @return 压缩后的消息列表（若无需压缩则返回原列表引用）
     */
    public List<Message> compress(List<Message> messages) {
        if (messages.size() < COMPRESS_MIN_SIZE) return messages;

        String existingSummary = findExistingSummary(messages);
        int keepStart = findKeepStart(messages);
        if (keepStart <= 1) return messages;

        StringBuilder rawBuilder = new StringBuilder();
        if (existingSummary != null) {
            rawBuilder.append("【已有摘要】\n").append(existingSummary).append("\n\n");
            rawBuilder.append("【新对话内容】\n");
        } else if (!summaryChain.isEmpty()) {
            rawBuilder.append("【上一代摘要】\n").append(summaryChain.get(0)).append("\n\n");
            rawBuilder.append("【新对话内容】\n");
        }
        for (int i = 1; i < keepStart; i++) {
            Message msg = messages.get(i);
            String content;
            if (msg instanceof UserMessage u) {
                content = u.getText();
            } else if (msg instanceof AssistantMessage a) {
                content = a.getText() != null ? a.getText() : "(工具调用)";
            } else if (msg instanceof ToolResponseMessage) {
                content = "(已压缩)";
            } else {
                content = "";
            }
            if (!content.isBlank()) {
                rawBuilder.append(content).append("\n");
            }
        }

        String rawText = rawBuilder.toString();
        if (rawText.isBlank()) return messages;

        String inputForSummary = rawText;
        boolean truncated = false;
        if (rawText.length() > MAX_SUMMARY_INPUT_CHARS) {
            inputForSummary = rawText.substring(0, MAX_SUMMARY_INPUT_CHARS);
            truncated = true;
            log.warn("[Compressor] 摘要输入过长: {} > {}", rawText.length(), MAX_SUMMARY_INPUT_CHARS);
        }

        String summary;
        try {
            ChatResponse summaryResponse = chatModel.call(new Prompt(
                    SUMMARY_PROMPT + "\n\n【对话内容】\n" + inputForSummary));
            summary = summaryResponse.getResult().getOutput().getText();
            if (summary == null || summary.isBlank()) {
                log.warn("[Compressor] 摘要生成为空");
                return messages;
            }
            if (truncated) {
                summary += "\n\n(部分对话因过长被截断)";
            }

            summaryGeneration++;
            synchronized (summaryChain) {
                if (summaryGeneration <= 1) {
                    summaryChain.add(summary);
                } else {
                    summaryChain.set(0, summary);
                }
            }
            log.info("[Compressor] 摘要链: 第{}代, 长度={}", summaryGeneration, summary.length());

            if (summaryStore != null) {
                try {
                    summaryStore.store(null, null, null, summary, summaryGeneration);
                } catch (Exception e) {
                    log.warn("[Compressor] 摘要持久化失败: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[Compressor] 摘要生成失败: {}", e.getMessage());
            return messages;
        }

        List<Message> compressed = new ArrayList<>();
        compressed.add(messages.get(0));
        compressed.add(new UserMessage("以下是对之前对话的摘要：\n\n" + summary));
        compressed.add(new AssistantMessage("好的，我已理解之前的对话内容，继续当前任务。"));
        for (int i = keepStart; i < messages.size(); i++) {
            compressed.add(messages.get(i));
        }
        return compressed;
    }

    /** 检测消息列表中是否已存在摘要。 */
    public static String findExistingSummary(List<Message> messages) {
        for (Message msg : messages) {
            if (msg instanceof UserMessage u) {
                String text = u.getText();
                if (text != null && text.startsWith("以下是对之前对话的摘要：")) {
                    return text.replace("以下是对之前对话的摘要：\n\n", "").trim();
                }
            }
        }
        return null;
    }

    /** 从末尾向前扫描，找到保留的起始索引。 */
    public static int findKeepStart(List<Message> messages) {
        int count = 0;
        int i = messages.size() - 1;
        if (i >= 0 && !(messages.get(i) instanceof ToolResponseMessage)) {
            i--;
        }
        while (i >= 0 && count < 3) {
            if (messages.get(i) instanceof ToolResponseMessage) {
                count++;
            }
            i--;
        }
        return Math.max(i + 1, 1);
    }
}
