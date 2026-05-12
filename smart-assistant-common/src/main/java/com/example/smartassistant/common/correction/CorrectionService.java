/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.correction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户修正记录服务
 * <p>
 * 管理各 Agent 的全局纠错记录文件，以 MD 格式存储在 {@code data/corrections/} 目录下。
 * 每个 Agent 一个文件（travel.md / food.md / general.md），所有用户共享。
 * <p>
 * 工具方法：
 * <ul>
 *   <li>{@link #queryCorrections(String, String)} — 按主题查询相关修正</li>
 *   <li>{@link #appendCorrection(String, String, String, String, long)} — 追加新修正</li>
 * </ul>
 */
@Service
public class CorrectionService {

    private static final Logger log = LoggerFactory.getLogger(CorrectionService.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 修正记录存储根目录 */
    private final Path correctionsDir;

    public CorrectionService(@Value("${app.data.dir:data}") String baseDataDir) {
        this.correctionsDir = Paths.get(baseDataDir, "corrections");
    }

    /**
     * 按主题查询 Agent 的修正记录
     *
     * @param agentName Agent 名称（travel / food / general）
     * @param topic     查询主题
     * @return 匹配的修正记录文本，无匹配时返回空字符串
     */
    public String queryCorrections(String agentName, String topic) {
        Path filePath = correctionsDir.resolve(agentName + ".md");
        if (!Files.exists(filePath)) {
            log.debug("[Correction] 修正文件不存在: {}", filePath);
            return "";
        }

        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            return matchCorrections(content, topic);
        } catch (IOException e) {
            log.warn("[Correction] 读取修正文件失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 追加一条修正记录到 Agent 的修正文件
     *
     * @param agentName    Agent 名称
     * @param topic        主题
     * @param errorContent 错误内容
     * @param correctContent 正确信息
     * @param userId       来源用户 ID
     */
    public void appendCorrection(String agentName, String topic, String errorContent,
                                  String correctContent, long userId) {
        try {
            Path dir = correctionsDir;
            Files.createDirectories(dir);

            Path filePath = dir.resolve(agentName + ".md");

            String today = LocalDate.now().format(DATE_FMT);
            String entry = String.format(
                    "\n> 主题: %s\n> 错误: %s\n> 正确: %s\n> 来源: userId=%d\n",
                    topic, errorContent, correctContent, userId);

            // 追加写入
            Files.writeString(filePath, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            log.info("[Correction] 修正已记录: agent={}, topic={}, userId={}",
                    agentName, topic, userId);

        } catch (IOException e) {
            log.warn("[Correction] 写入修正文件失败: {}", e.getMessage());
        }
    }

    /**
     * 从修正文件内容中匹配与主题相关的记录
     * <p>
     * 匹配策略：按 > 条目分割，每个条目尝试与 topic 做关键词匹配。
个匹配则返回所有记录（少量修正时 LLM 自行过滤）。
     */
    private String matchCorrections(String content, String topic) {
        // 按 "> 主题:" 分割条目
        Pattern entryPattern = Pattern.compile("> 主题:\\s*([^\\n]+)\\n> 错误:\\s*([^\\n]*)\\n> 正确:\\s*([^\\n]*)",
                Pattern.MULTILINE);
        Matcher matcher = entryPattern.matcher(content);

        List<String> matched = new ArrayList<>();
        while (matcher.find()) {
            String entryTopic = matcher.group(1).trim();
            String error = matcher.group(2).trim();
            String correct = matcher.group(3).trim();

            // 关键词匹配：条目主题包含查询主题，或查询主题包含条目主题
            if (topic != null && !topic.isBlank() &&
                    (entryTopic.contains(topic) || topic.contains(entryTopic))) {
                matched.add(String.format("- %s: 错误=\"%s\"，修正为=\"%s\"", entryTopic, error, correct));
            }
        }

        if (matched.isEmpty()) {
            return "";
        }

        return "【历史修正记录】\n" + String.join("\n", matched) + "\n";
    }

    /**
     * 获取 Agent 修正文件的格式化摘要（用于注入 system prompt）
     *
     * @param agentName Agent 名称
     * @return 所有修正记录的格式化文本
     */
    public String getAllCorrectionsSummary(String agentName) {
        Path filePath = correctionsDir.resolve(agentName + ".md");
        if (!Files.exists(filePath)) {
            return "";
        }
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            Pattern entryPattern = Pattern.compile(
                    "> 主题:\\s*([^\\n]+)\\n> 错误:\\s*([^\\n]*)\\n> 正确:\\s*([^\\n]*)",
                    Pattern.MULTILINE);
            Matcher matcher = entryPattern.matcher(content);

            List<String> entries = new ArrayList<>();
            while (matcher.find()) {
                String topic = matcher.group(1).trim();
                String correct = matcher.group(3).trim();
                if (!topic.isBlank() && !correct.isBlank()) {
                    entries.add(String.format("- %s: %s", topic, correct));
                }
            }

            if (entries.isEmpty()) return "";
            return "【用户历史纠错参考】\n" + String.join("\n", entries) + "\n";

        } catch (IOException e) {
            log.warn("[Correction] 读取修正摘要失败: {}", e.getMessage());
            return "";
        }
    }
}
