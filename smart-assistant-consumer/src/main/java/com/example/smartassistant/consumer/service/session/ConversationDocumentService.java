package com.example.smartassistant.consumer.service.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 对话文档沉淀服务（文件存储版）
 * 将有价值的对话保存为用户专属的 Markdown 记忆文件
 * <p>
 * 目录结构：data/users/{userId}/memories/{yyyy-MM-dd}_{sessionId}.md
 * 每个文件包含一次有价值对话的完整内容，附带 YAML 元信息。
 */
@Service
public class ConversationDocumentService {

    private static final Logger log = LoggerFactory.getLogger(ConversationDocumentService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ★ 叙事摘要阈值：内容长度和轮数都达标才触发 LLM
    private static final int MIN_CONTENT_FOR_SUMMARIZE = 1000;
    private static final int MIN_TURN_FOR_SUMMARIZE = 3;

    private final String basePath;

    // ★ 叙事摘要服务
    private final ConversationSummarizationService summarizationService;

    @Autowired
    public ConversationDocumentService(
            @Value("${app.data.dir:data/users}") String basePath,
            ConversationSummarizationService summarizationService) {
        this.basePath = basePath;
        this.summarizationService = summarizationService;
    }

    /**
     * 异步保存有价值对话为用户记忆文件
     */
    @Async("taskExecutor")
    public void saveValuableConversation(ConversationValueService.ConversationValueContext ctx) {
        try {
            Path userDir = Paths.get(basePath, String.valueOf(ctx.userId()), "memories");
            Files.createDirectories(userDir);

            String filename = LocalDate.now().format(DATE_FMT) + "_" + sanitize(ctx.sessionId()) + ".md";
            Path filePath = userDir.resolve(filename);

            String content = buildMemoryFile(ctx);
            Files.writeString(filePath, content, StandardCharsets.UTF_8);

            log.info("[UserMemory] 记忆已保存: userId={}, file={}", ctx.userId(), filename);

        } catch (Exception e) {
            log.warn("[UserMemory] 保存记忆失败: userId={}, error={}", ctx.userId(), e.getMessage());
        }
    }

    /**
     * 构建记忆文件内容（Markdown + YAML 元信息）
     * <p>
     * - 内容较短或轮数较少时，直接写入原文（LLM 性价比低）
     * - 内容较长且轮数达标时，LLM 摘要为叙事形式
     */
    private String buildMemoryFile(ConversationValueService.ConversationValueContext ctx) {
        String content = ctx.content();

        // ★ 只有轮数和内容长度都达标才触发 LLM 摘要
        boolean shouldNarrate = content != null
                && content.length() >= MIN_CONTENT_FOR_SUMMARIZE
                && ctx.turnCount() >= MIN_TURN_FOR_SUMMARIZE;

        String body;
        String format;
        if (shouldNarrate) {
            body = summarizationService.summarize(content);
            format = "narrative";
            log.debug("[UserMemory] 叙事摘要: sessionId={}, raw={}chars, summary={}chars",
                    ctx.sessionId(), content.length(), body.length());
        } else {
            body = content;
            format = "raw";
            log.debug("[UserMemory] 原文保存(未达摘要阈值): sessionId={}, len={}, turns={}",
                    ctx.sessionId(), content.length(), ctx.turnCount());
        }

        return "---\n" +
                "created_at: " + System.currentTimeMillis() + "\n" +
                "agent: " + ctx.agentName() + "\n" +
                "intent: " + (ctx.intentTag() != null ? ctx.intentTag() : "") + "\n" +
                "session: " + ctx.sessionId() + "\n" +
                "turn_count: " + ctx.turnCount() + "\n" +
                "format: " + format + "\n" +
                "---\n\n" +
                body + "\n";
    }

    private String sanitize(String input) {
        if (input == null) return "unknown";
        return input.replaceAll("[^a-zA-Z0-9_\\-]", "_").substring(0, Math.min(50, input.length()));
    }

}
