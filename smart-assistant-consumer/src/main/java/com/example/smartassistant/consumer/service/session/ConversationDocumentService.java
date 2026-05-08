package com.example.smartassistant.consumer.service.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 对话文档沉淀服务（文件存储版）
 * 将有价值的对话保存为用户专属的 Markdown 记忆文件
 * <p>
 * 目录结构：data/user-memories/{userId}/{yyyy-MM-dd}_{sessionId}.md
 * 每个文件包含一次有价值对话的完整内容，附带 YAML 元信息。
 */
@Service
public class ConversationDocumentService {

    private static final Logger log = LoggerFactory.getLogger(ConversationDocumentService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.memory.storage-path:data/user-memories}")
    private String storagePath;

    /**
     * 异步保存有价值对话为用户记忆文件
     */
    @Async("taskExecutor")
    public void saveValuableConversation(ConversationValueService.ConversationValueContext ctx) {
        try {
            Path userDir = Paths.get(storagePath, String.valueOf(ctx.userId()));
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
     */
    private String buildMemoryFile(ConversationValueService.ConversationValueContext ctx) {
        return new StringBuilder()
                .append("---\n")
                .append("created_at: ").append(System.currentTimeMillis()).append("\n")
                .append("agent: ").append(ctx.agentName()).append("\n")
                .append("intent: ").append(ctx.intentTag() != null ? ctx.intentTag() : "").append("\n")
                .append("session: ").append(ctx.sessionId()).append("\n")
                .append("---\n\n")
                .append(ctx.content()).append("\n")
                .toString();
    }

    /**
     * 检索用户记忆（关键词匹配）
     * @param userId 用户 ID
     * @param keywords 关键词列表（命中任一即返回）
     * @param limit 返回条数
     */
    public List<MemoryEntry> searchMemories(Long userId, List<String> keywords, int limit) {
        Path userDir = Paths.get(storagePath, String.valueOf(userId));
        if (!Files.isDirectory(userDir)) return List.of();

        List<MemoryEntry> results = new ArrayList<>();

        try (Stream<Path> files = Files.list(userDir)) {
            files.filter(p -> p.toString().endsWith(".md"))
                    .forEach(file -> {
                        try {
                            String content = Files.readString(file, StandardCharsets.UTF_8);
                            String lowerContent = content.toLowerCase();

                            boolean matched = keywords.stream()
                                    .anyMatch(kw -> kw != null && lowerContent.contains(kw.toLowerCase()));

                            if (matched) {
                                String filename = file.getFileName().toString();
                                String summary = extractSummary(content);
                                results.add(new MemoryEntry(filename, content, summary));
                            }
                        } catch (IOException e) {
                            log.warn("[UserMemory] 读取失败: {}", file);
                        }
                    });
        } catch (IOException e) {
            log.warn("[UserMemory] 检索用户目录失败: userId={}", userId);
        }

        // 按文件名排序（时间倒序）
        results.sort((a, b) -> b.filename().compareTo(a.filename()));
        return results.stream().limit(limit).collect(Collectors.toList());
    }

    /**
     * 检索用户记忆（全文匹配，直接搜索文本）
     */
    public List<MemoryEntry> searchMemories(Long userId, String query, int limit) {
        return searchMemories(userId, List.of(query), limit);
    }

    /**
     * 获取用户记忆总数
     */
    public long countMemories(Long userId) {
        Path userDir = Paths.get(storagePath, String.valueOf(userId));
        if (!Files.isDirectory(userDir)) return 0;
        try (Stream<Path> files = Files.list(userDir)) {
            return files.filter(p -> p.toString().endsWith(".md")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private String sanitize(String input) {
        if (input == null) return "unknown";
        return input.replaceAll("[^a-zA-Z0-9_\\-]", "_").substring(0, Math.min(50, input.length()));
    }

    private String extractSummary(String content) {
        // 跳过 YAML frontmatter，取正文前 100 字
        int bodyStart = content.indexOf("---\n", content.indexOf("---\n") + 4);
        if (bodyStart < 0) bodyStart = 0;
        String body = content.substring(bodyStart + 4).trim();
        return body.length() > 100 ? body.substring(0, 100) + "..." : body;
    }

    /**
     * 记忆条目
     */
    public record MemoryEntry(String filename, String content, String summary) {}
}
