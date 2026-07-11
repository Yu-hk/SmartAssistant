/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 独立记忆服务（本地 Markdown 文件存储）。
 *
 * <p>每条记忆在文件中附带保存日期，{@link #getAllFormatted(String, String)} 根据距今天数
 * 附加老化警告（7~30 天 ⚠️，超过 30 天 ⚠️⚠️ 可能已过时）。
 * 超过 {@link #MAX_DISPLAY_ENTRIES} 条时截断输出，防止 prompt 膨胀。</p>
 *
 * <p>参考 Claude Code 记忆机制的关键设计：<strong>时间感知 + 主动验证</strong>，
 * 记忆是"历史快照"而非"真理"，超过一定时间应附加时效性提醒。</p>
 */
@Component
public class AgentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryService.class);

    private static final String LIST_PREFIX = "- ";
    private static final String KV_SEPARATOR = ": ";
    /** 值和时间戳的内部分隔符 */
    private static final String TS_SUFFIX = " || ";
    /** 日期格式 */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** ⭐ 超过此天数的记忆标记 ⚠️ 警告 */
    private static final long WARN_DAYS = 7;
    /** ⭐ 超过此天数的记忆标记 ⚠️⚠️ 严重过时 */
    private static final long STALE_DAYS = 30;

    /** ⭐ 单次格式化输出的最大条目数，超过后截断 */
    private static final int MAX_DISPLAY_ENTRIES = 10;

    private final Path basePath;

    public AgentMemoryService(@Value("${app.data.dir:data/users}") String basePath) {
        this.basePath = Paths.get(basePath);
    }

    public void save(String agent, String userId, String key, String value) {
        if (agent == null || userId == null || key == null) return;
        try {
            Path file = getMemoryFile(agent, userId);
            Map<String, String> memories = loadFile(file);
            // 编码值和时间戳：value || YYYY-MM-DD
            memories.put(key, (value != null ? value : "") + TS_SUFFIX + LocalDate.now().format(DATE_FMT));
            writeFile(file, memories);
            log.debug("[AgentMemory] 保存: agent={}, userId={}, key={}, value={}", agent, userId, key, value);
        } catch (Exception e) {
            log.warn("[AgentMemory] 保存失败: agent={}, userId={}, key={}, error={}", agent, userId, key, e.getMessage());
        }
    }

    public String get(String agent, String userId, String key) {
        if (agent == null || userId == null || key == null) return null;
        try {
            Map<String, String> memories = loadFile(getMemoryFile(agent, userId));
            String raw = memories.get(key);
            if (raw == null) return null;
            return stripTimestamp(raw);
        } catch (Exception e) {
            return null;
        }
    }

    public void delete(String agent, String userId, String key) {
        if (agent == null || userId == null || key == null) return;
        try {
            Path file = getMemoryFile(agent, userId);
            Map<String, String> memories = loadFile(file);
            if (memories.remove(key) != null) {
                writeFile(file, memories);
                log.debug("[AgentMemory] 删除: agent={}, userId={}, key={}", agent, userId, key);
            }
        } catch (Exception e) {
            log.warn("[AgentMemory] 删除失败: agent={}, userId={}, key={}, error={}", agent, userId, key, e.getMessage());
        }
    }

    /**
     * 获取该 Agent + 用户的所有记忆，含老化警告和条目截断。
     *
     * <p>老化规则：</p>
     * <ul>
     *   <li>保存至今 ≤{@value #WARN_DAYS} 天 → 正常显示</li>
     *   <li>{@value #WARN_DAYS}~{@value #STALE_DAYS} 天 → 附加 ⚠️ 警告</li>
     *   <li>超过 {@value #STALE_DAYS} 天 → 附加 ⚠️⚠️ 可能已过时</li>
     * </ul>
     *
     * <p>条目截断：超过 {@value #MAX_DISPLAY_ENTRIES} 条时只输出前半 + 截断提示。</p>
     */
    /**
     * 获取该 Agent + 用户的所有记忆，含老化警告和条目截断。
     * 参考 {@link #getAllFormatted(String, String, String)} 可传入上下文做相关性排序。
     */
    public String getAllFormatted(String agent, String userId) {
        return getAllFormatted(agent, userId, null);
    }

    /**
     * 获取结构化状态锚点（始终返回，不依赖是否有记忆）。
     *
     * <p>状态锚点是每轮对话中固定注入的结构化信息锚点，防止上下文漂移。
     * 即使没有用户偏好，也会返回"未登录访客"占位锚点。
     * 参考文章⑧：状态管理区是"不被稀释的系统锚点"。</p>
     */
    public String getStateAnchor(String userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 状态锚点\n");
        if (userId != null && !userId.isBlank() && !"null".equals(userId)) {
            sb.append("- 当前用户：").append(userId).append("\n");
            sb.append("- 偏好状态：").append(hasMemory("order", userId) || hasMemory("product", userId)
                    || hasMemory("general", userId) ? "有保存的偏好" : "无偏好").append("\n");
        } else {
            sb.append("- 当前用户：未登录访客\n");
        }
        return sb.toString();
    }

    /**
     * 获取该 Agent + 用户的所有记忆，按问题上下文排序。
     *
     * <p>当传入 {@code context} 时，记忆条目按与上下文的匹配度排序
     * （基于键名+值的关键词命中），使最相关的偏好排在前面。
     * 这是"小模型语义选择"的轻量实现——用关键词匹配替代 LLM 调用。</p>
     *
     * <p>老化规则：</p>
     * <ul>
     *   <li>保存至今 ≤{@value #WARN_DAYS} 天 → 正常显示</li>
     *   <li>{@value #WARN_DAYS}~{@value #STALE_DAYS} 天 → 附加 ⚠️ 警告</li>
     *   <li>超过 {@value #STALE_DAYS} 天 → 附加 ⚠️⚠️ 可能已过时</li>
     * </ul>
     *
     * <p>条目截断：超过 {@value #MAX_DISPLAY_ENTRIES} 条时只输出前半 + 截断提示。</p>
     *
     * @param agent   Agent 名称
     * @param userId  用户 ID
     * @param context 当前问题上下文（用于相关性排序，可为 null）
     * @return 格式化文本；无记忆时返回空字符串
     */
    public String getAllFormatted(String agent, String userId, String context) {
        if (agent == null || userId == null) return "";
        try {
            Map<String, String> memories = loadFile(getMemoryFile(agent, userId));
            if (memories.isEmpty()) return "";

            // ⭐ 按上下文相关性排序（关键词匹配，LLM-free 轻量语义选择）
            String ctx = (context != null) ? context.toLowerCase() : "";
            List<Map.Entry<String, String>> entries = new ArrayList<>(memories.entrySet());
            if (!ctx.isBlank()) {
                entries.sort((a, b) -> {
                    int sa = relevanceScore(a, ctx);
                    int sb = relevanceScore(b, ctx);
                    return Integer.compare(sb, sa);
                });
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[用户偏好]\n");

            int count = 0;
            int total = memories.size();
            boolean truncated = total > MAX_DISPLAY_ENTRIES;
            int limit = truncated ? MAX_DISPLAY_ENTRIES / 2 : total;

            LocalDate today = LocalDate.now();

            for (Map.Entry<String, String> entry : entries) {
                if (count >= limit) break;
                count++;

                String raw = entry.getValue();
                String value = stripTimestamp(raw);
                LocalDate savedDate = parseDate(raw);
                if (value == null || value.isBlank()) continue;

                long daysSinceSaved = savedDate != null ? ChronoUnit.DAYS.between(savedDate, today) : 0;
                String prefix = daysSinceSaved > STALE_DAYS ? "⚠️⚠️ " : (daysSinceSaved > WARN_DAYS ? "⚠️ " : "");
                sb.append(prefix).append("- ").append(formatKeyName(entry.getKey())).append("：").append(value);
                if (daysSinceSaved > WARN_DAYS) {
                    sb.append(" (").append(daysSinceSaved).append("天前)");
                }
                sb.append("\n");
            }

            if (truncated) {
                sb.append("\n  ⚠️ 共 ").append(total).append(" 条偏好，仅显示前 ").append(limit).append(" 条\n");
                // ⭐ 提供剩余条目的 key-only 索引，Agent 可调用 recallMemories 获取详情
                sb.append("  (更多键名：");
                int indexCount = 0;
                for (Map.Entry<String, String> entry : entries) {
                    String raw = entry.getValue();
                    String value = stripTimestamp(raw);
                    if (value == null || value.isBlank()) continue;
                    indexCount++;
                    if (indexCount <= limit) continue;
                    sb.append(entry.getKey()).append(", ");
                }
                if (sb.charAt(sb.length() - 2) == ',') {
                    sb.setLength(sb.length() - 2);
                }
                sb.append(" — 可调用 recallMemories 获取详情)\n");
            }

            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public boolean hasMemory(String agent, String userId) {
        if (agent == null || userId == null) return false;
        try {
            return Files.exists(getMemoryFile(agent, userId));
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 文件操作 ====================

    private Path getMemoryFile(String agent, String userId) {
        return basePath.resolve(userId).resolve(agent + "-memory.md");
    }

    /** 从 Markdown 文件加载全部记忆；文件不存在时返回空 Map */
    private Map<String, String> loadFile(Path file) {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) return new LinkedHashMap<>();

            Map<String, String> memories = new LinkedHashMap<>();
            for (String line : content.split("\n")) {
                line = line.trim();
                if (line.startsWith(LIST_PREFIX) && line.contains(KV_SEPARATOR)) {
                    String rest = line.substring(LIST_PREFIX.length());
                    int sepIdx = rest.indexOf(KV_SEPARATOR);
                    if (sepIdx > 0) {
                        String key = rest.substring(0, sepIdx).trim();
                        String fullValue = rest.substring(sepIdx + KV_SEPARATOR.length()).trim();
                        if (!key.isEmpty() && !fullValue.isEmpty()) {
                            memories.put(key, fullValue);
                        }
                    }
                }
            }
            return memories;
        } catch (IOException e) {
            log.warn("[AgentMemory] 读取文件失败: {}, error={}", file, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** 写入 Markdown 文件 */
    private void writeFile(Path file, Map<String, String> memories) {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            String agentName = file.getFileName().toString().replace("-memory.md", "");
            sb.append("# ").append(capitalize(agentName)).append(" Agent 用户偏好\n\n");
            for (Map.Entry<String, String> entry : memories.entrySet()) {
                String fullValue = entry.getValue();
                if (fullValue != null && !fullValue.isBlank()) {
                    sb.append(LIST_PREFIX).append(entry.getKey()).append(KV_SEPARATOR).append(fullValue).append("\n");
                }
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[AgentMemory] 写入文件失败: {}, error={}", file, e.getMessage());
        }
    }

    // ==================== 时间戳工具 ====================

    /** 从存储的完整值中剥离时间戳后缀 */
    private static String stripTimestamp(String raw) {
        if (raw == null) return null;
        int idx = raw.lastIndexOf(TS_SUFFIX);
        if (idx > 0) return raw.substring(0, idx);
        // 兼容旧格式：无时间戳的行
        return raw;
    }

    /** 从存储的完整值中解析保存日期 */
    private static LocalDate parseDate(String raw) {
        if (raw == null) return null;
        int idx = raw.lastIndexOf(TS_SUFFIX);
        if (idx < 0) return null;
        String dateStr = raw.substring(idx + TS_SUFFIX.length()).trim();
        try {
            return LocalDate.parse(dateStr, DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ==================== 键名格式化 ====================

    private static String formatKeyName(String key) {
        return switch (key) {
            case "preferWindowSeat", "preferWindow" -> "偏好窗口座位";
            case "preferAisleSeat", "preferAisle" -> "偏好过道座位";
            case "frequentRoute" -> "常用出行路线";
            case "frequentDeparture" -> "常用出发地";
            case "frequentDestination" -> "常用目的地";
            case "preferredCarrier", "preferCarrier" -> "偏好承运商";
            case "preferSeatType" -> "偏好座位等级";
            case "preferPaymentMethod" -> "偏好支付方式";
            case "maxPrice", "priceLimit" -> "价格上限";
            case "frequentCategory" -> "常看品类";
            case "replyStyle" -> "回复风格偏好";
            default -> key;
        };
    }

    // ==================== 上下文相关性评分 ====================

    /** 计算记忆条目与上下文的匹配得分（关键词命中数） */
    private static int relevanceScore(Map.Entry<String, String> entry, String context) {
        String combined = (entry.getKey() + " " + formatKeyName(entry.getKey()) + " " + stripTimestamp(entry.getValue()))
                .toLowerCase();
        int score = 0;
        // 以空格/逗号/句号分割上下文为 tokens
        String[] tokens = context.split("[\\s，。、,.;:！？]+");
        for (String token : tokens) {
            if (token.length() < 2) continue; // 跳过单字
            if (combined.contains(token)) {
                score++;
            }
        }
        return score;
    }
}
