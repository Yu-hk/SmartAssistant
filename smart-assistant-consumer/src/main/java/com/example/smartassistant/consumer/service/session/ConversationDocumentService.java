/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.session;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 对话文档沉淀服务（文件存储版 — session 优先增量追加模式）
 * <p>
 * 将每个有价值对话轮次追加到同一个记忆文件，保留完整 session 记忆。
 * session 始终追加到同一文件（跨天不换文件），文件名为首次创建的日期。
 * <p>
 * 目录结构：data/users/{userId}/memories/{yyyy-MM-dd}_{sessionId}.md
 * <p>
 * 文件结构：
 * <pre>
 * ---
 * created_at: 1746680000000
 * session: session_abc
 * turn_range: 3-5
 * entries: 3
 * ---
 * 
 * > Turn 3 | narrative | intent: 景点查询
 * 
 * 用户查询了北京景点推荐...
 * 
 * ---
 * 
 * > Turn 4 | raw | intent: 美食推荐
 * 
 * 用户：还有别的推荐吗？
 * 助手：推荐东来顺...
 * </pre>
 */
@Service
public class ConversationDocumentService {

    private static final Logger log = LoggerFactory.getLogger(ConversationDocumentService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ★ 叙事摘要阈值：内容长度和轮数都达标才触发 LLM
    private static final int MIN_CONTENT_FOR_SUMMARIZE = 1000;
    private static final int MIN_TURN_FOR_SUMMARIZE = 3;

    // ★ 文件锁缓存，按 sessionId 粒度同步（使用 Caffeine 缓存，30 分钟自动过期，最大 5000 条目）
    // 使用 ReentrantLock 替代 synchronized，避免虚拟线程 Carrier 线程 pin
    private final Cache<String, ReentrantLock> fileLocks = Caffeine.newBuilder()
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .maximumSize(5000)
            .build();

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
     * 异步保存有价值对话为用户记忆文件（session 优先的增量追加）
     * <p>
     * - session 优先：同 session 始终追加到同一文件，跨天不换文件
     * - 文件不存在时创建并写入第一个条目（文件名带当天日期）
     * - 文件已存在时读取旧内容，追加新条目，更新 frontmatter
     * - 通过 sessionId 粒度锁保证并发安全
     */
    @Async
    public void saveValuableConversation(ConversationValueService.ConversationValueContext ctx) {
        try {
            Path userDir = Paths.get(basePath, String.valueOf(ctx.userId()), "memories");
            Files.createDirectories(userDir);

            String sessionKey = sanitize(ctx.sessionId());
            String entryContent = buildEntryContent(ctx);

            // ★ 同步写（session 粒度，防并发读-改-写冲突）
            // 使用 ReentrantLock 替代 synchronized，避免虚拟线程 pin
            ReentrantLock lock = getFileLock(ctx.sessionId());
            lock.lock();
            try {
                // ★ session 优先：查找已有记忆文件（跨天不换文件）
                Path existingFile = findExistingSessionFile(userDir, sessionKey);

                if (existingFile != null) {
                    appendToMemoryFile(existingFile, ctx, entryContent);
                } else {
                    // 不存在则创建新文件（带当前日期）
                    String filename = LocalDate.now().format(DATE_FMT) + "_" + sessionKey + ".md";
                    createMemoryFile(userDir.resolve(filename), ctx, entryContent);
                }
            } finally {
                lock.unlock();
            }

            log.info("[UserMemory] 记忆已保存: userId={}, sessionId={}", ctx.userId(), ctx.sessionId());

        } catch (Exception e) {
            log.warn("[UserMemory] 保存记忆失败: userId={}, error={}", ctx.userId(), e.getMessage());
        }
    }

    // ========== 条目构建 ==========

    /**
     * 生成本轮对话的条目内容（摘要或原文）
     */
    private String buildEntryContent(ConversationValueService.ConversationValueContext ctx) {
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

        // 条目头部：> Turn {n} | {format} | intent: {intentTag}
        String intentTag = ctx.intentTag() != null ? ctx.intentTag() : "";
        String fromCache = ctx.fromCache() ? " | fromCache: true" : "";

        return "> Turn " + ctx.turnCount() + " | " + format + " | intent: " + intentTag + fromCache + "\n\n"
                + body + "\n";
    }

    // ========== 文件写入 ==========

    /**
     * 创建新的记忆文件（首个条目）
     */
    private void createMemoryFile(Path filePath,
                                  ConversationValueService.ConversationValueContext ctx,
                                  String entryContent) throws Exception {
        String frontmatter = buildFrontmatter(
                System.currentTimeMillis(),
                ctx.sessionId(),
                ctx.turnCount(),
                ctx.turnCount(),
                1
        );
        Files.writeString(filePath, frontmatter + entryContent, StandardCharsets.UTF_8);
    }

    /**
     * 追加条目到已有记忆文件
     * <p>
     * 流程：读取 → 解析 frontmatter → 更新元信息 → 追加新条目 → 写回
     */
    private void appendToMemoryFile(Path filePath,
                                    ConversationValueService.ConversationValueContext ctx,
                                    String entryContent) throws Exception {
        String existing = Files.readString(filePath, StandardCharsets.UTF_8);

        // 解析 frontmatter：找到第二个 ---\n 的位置
        int firstFm = existing.indexOf("---\n");
        if (firstFm == -1) {
            log.warn("[UserMemory] 文件格式异常，缺少开头的 ---: {}", filePath);
            return;
        }
        int secondFm = existing.indexOf("---\n", firstFm + 4);
        if (secondFm == -1) {
            log.warn("[UserMemory] 文件格式异常，缺少结尾的 ---: {}", filePath);
            return;
        }
        int frontmatterEnd = secondFm + 4; // 包含 ---\n

        String oldFrontmatter = existing.substring(0, frontmatterEnd);
        String oldBody = existing.substring(frontmatterEnd);

        // 从旧 frontmatter 提取元信息
        long createdAt = parseLongField(oldFrontmatter, "created_at:", System.currentTimeMillis());
        int minTurn = parseIntField(oldFrontmatter, "turn_range:\\s*(\\d+)", ctx.turnCount());
        int oldEntries = parseIntField(oldFrontmatter, "entries:\\s*(\\d+)", 1);

        // 构建新 frontmatter（保留原有 created_at，更新 turn_range 和 entries）
        String newFrontmatter = buildFrontmatter(
                createdAt,
                ctx.sessionId(),
                minTurn,
                ctx.turnCount(),
                oldEntries + 1
        );

        // 新文件 = 新 frontmatter + 旧 body + 分隔符 + 新条目
        String newContent = newFrontmatter + oldBody + "\n---\n\n" + entryContent;

        Files.writeString(filePath, newContent, StandardCharsets.UTF_8);
        log.info("[UserMemory] 追加条目: sessionId={}, turn={}, totalEntries={}",
                ctx.sessionId(), ctx.turnCount(), oldEntries + 1);
    }

    // ========== Frontmatter 工具 ==========

    /**
     * 构建 YAML frontmatter
     */
    private String buildFrontmatter(long createdAt, String sessionId,
                                    int turnMin, int turnMax, int entries) {
        return "---\n"
                + "created_at: " + createdAt + "\n"
                + "session: " + sessionId + "\n"
                + "turn_range: " + turnMin + "-" + turnMax + "\n"
                + "entries: " + entries + "\n"
                + "---\n\n";
    }

    /**
     * 从 frontmatter 文本中解析整数字段
     */
    private int parseIntField(String frontmatter, String regex, int defaultValue) {
        Matcher m = Pattern.compile(regex).matcher(frontmatter);
        return m.find() ? Integer.parseInt(m.group(1)) : defaultValue;
    }

    /**
     * 从 frontmatter 文本中解析长整数字段
     */
    private long parseLongField(String frontmatter, String prefix, long defaultValue) {
        for (String line : frontmatter.split("\n")) {
            if (line.startsWith(prefix)) {
                String val = line.substring(prefix.length()).trim();
                try {
                    return Long.parseLong(val);
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    // ========== 工具方法 ==========

    /**
     * 查找 session 已有的记忆文件（按 sessionId 匹配，忽略日期前缀）
     * <p>
     * 匹配模式：{yyyy-MM-dd}_{sessionKey}.md
     * 如果找到多个（不应发生），返回第一个。
     *
     * @return 匹配的文件路径，不存在时返回 null
     */
    private Path findExistingSessionFile(Path userDir, String sessionKey) {
        Pattern pattern = Pattern.compile(
                "^\\d{4}-\\d{2}-\\d{2}_" + Pattern.quote(sessionKey) + "\\.md$");
        try (Stream<Path> stream = Files.list(userDir)) {
            return stream
                    .filter(p -> pattern.matcher(p.getFileName().toString()).matches())
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            // 目录首次使用尚不存在，或遍历异常
            return null;
        }
    }

    /**
     * 获取 sessionId 粒度的 ReentrantLock（使用 Caffeine 缓存，自动过期）
     */
    private ReentrantLock getFileLock(String sessionId) {
        return fileLocks.get(sessionId, k -> new ReentrantLock());
    }

    /**
     * 文件名字符净化
     */
    private String sanitize(String input) {
        if (input == null) return "unknown";
        return input.replaceAll("[^a-zA-Z0-9_\\-]", "_").substring(0, Math.min(50, input.length()));
    }
}
