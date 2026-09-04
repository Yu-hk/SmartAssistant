/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 用户反馈服务 — 采集用户对 Agent 回复的满意度评价。
 *
 * <p>反馈以 Markdown 文件追加存储在 {@code {app.data.dir}/feedback/{date}.md} 中，
 * 每条反馈记录包含：时间、用户ID、会话ID、问题、回复摘要、评分（like/dislike）。</p>
 *
 * <p>后续可接入 {@link com.example.smartassistant.common.rag.KnowledgeRetrievalService} 做反馈分析，
 * 或导出为业务测试集样本。</p>
 */
@Component
public class UserFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(UserFeedbackService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path feedbackDir;
    private final Path reviewQueueFile;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UserFeedbackService(@Value("${app.data.dir:data/users}") String basePath) {
        Path dataPath = Paths.get(basePath);
        Path dataRoot = dataPath.getParent() != null ? dataPath.getParent() : Paths.get(".");
        this.feedbackDir = dataRoot.resolve("feedback");
        this.reviewQueueFile = feedbackDir.resolve("review-queue.jsonl");
    }

    /**
     * 记录用户反馈。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @param question  用户问题
     * @param response  Agent 回复（截断至 200 字）
     * @param rating    评价：{@code like} 或 {@code dislike}
     * @param reason    用户补充原因（可选）
     */
    public void recordFeedback(String userId, String sessionId, String question,
                                String response, String rating, String reason) {
        try {
            Files.createDirectories(feedbackDir);
            Path file = feedbackDir.resolve(LocalDateTime.now().format(DATE_FMT) + ".md");

            String entry = buildEntry(userId, sessionId, question, response, rating, reason);
            Files.writeString(file, entry, StandardCharsets.UTF_8,
                    Files.exists(file) ? StandardOpenOption.APPEND : StandardOpenOption.CREATE);

            // 点踩反馈进入结构化人工复核队列；复核后再晋升为 Golden Case，避免未经确认的数据污染评测集。
            if ("dislike".equals(rating)) {
                appendReviewCandidate(userId, sessionId, question, response, reason);
            }

            log.info("[Feedback] 收到反馈: userId={}, rating={}", userId, rating);
        } catch (IOException e) {
            log.warn("[Feedback] 保存反馈失败: {}", e.getMessage());
        }
    }

    private void appendReviewCandidate(String userId, String sessionId, String question,
                                       String response, String reason) throws IOException {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("id", UUID.randomUUID().toString());
        candidate.put("createdAt", LocalDateTime.now().toString());
        candidate.put("status", "PENDING_REVIEW");
        candidate.put("userId", userId);
        candidate.put("sessionId", sessionId);
        candidate.put("question", question != null ? question : "");
        candidate.put("response", response != null ? response : "");
        candidate.put("reason", reason != null ? reason : "");
        Files.writeString(reviewQueueFile,
                objectMapper.writeValueAsString(candidate) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String buildEntry(String userId, String sessionId, String question,
                                      String response, String rating, String reason) {
        String ts = LocalDateTime.now().format(TS_FMT);
        String ratingEmoji = "like".equals(rating) ? "👍" : "👎";
        String responseSummary = response != null && response.length() > 200
                ? response.substring(0, 200) + "..." : response;

        return """
                ---
                > **时间**：%s
                > **用户**：%s
                > **会话**：%s
                > **评价**：%s

                **问题**：%s

                **回复摘要**：%s

                """.formatted(ts, userId, sessionId, ratingEmoji,
                        question != null ? question : "",
                        responseSummary != null ? responseSummary : "")
                + (reason != null && !reason.isBlank() ? "**用户补充**：%s\n\n".formatted(reason) : "")
                + "---\n\n";
    }
}
