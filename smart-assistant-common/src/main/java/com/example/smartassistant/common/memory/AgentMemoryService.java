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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 独立记忆服务（本地 Markdown 文件存储）。
 *
 * <p>为每个 Agent（order/product/general）提供用户粒度的键值记忆存储。
 * 记忆以 Markdown 文件存储在 {@code {app.data.dir}/{userId}/{agent}-memory.md} 中，
 * 与 Consumer 的记忆沉淀（{@code memories/*.md}）保持一致的格式风格。</p>
 *
 * <p>存储格式（Markdown 无序列表）：</p>
 * <pre>
 * # Agent 用户偏好
 *
 * - preferWindowSeat: 靠窗
 * - frequentRoute: 北京→上海
 * - preferPaymentMethod: 微信支付
 * </pre>
 *
 * <p>写入时机：Agent 工具调用成功后，由 Agent 自身调用 {@link #save(String, String, String, String)}。</p>
 * <p>读取时机：Agent 处理请求前，由 Controller 将记忆注入 system prompt 的上下文。</p>
 *
 * <p>遵循文章③的核心原则：<strong>"不记什么 > 记什么"</strong>，
 * 只记录可复用的用户偏好和习惯，不记录临时会话细节。</p>
 */
@Component
public class AgentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryService.class);

    /** 标记文件标题前缀 */
    private static final String FILE_TITLE_PREFIX = "# ";
    /** 无序列表前缀 */
    private static final String LIST_PREFIX = "- ";
    /** 键值分隔符 */
    private static final String KV_SEPARATOR = ": ";

    private final Path basePath;

    public AgentMemoryService(@Value("${app.data.dir:data/users}") String basePath) {
        this.basePath = Paths.get(basePath);
    }

    /**
     * 保存一条 Agent 记忆。
     *
     * @param agent  Agent 名称（如 {@code order}）
     * @param userId 用户 ID
     * @param key    记忆键（如 {@code preferWindowSeat}）
     * @param value  记忆值（如 {@code 靠窗}）
     */
    public void save(String agent, String userId, String key, String value) {
        if (agent == null || userId == null || key == null) return;
        try {
            Path file = getMemoryFile(agent, userId);
            Map<String, String> memories = loadFile(file);
            memories.put(key, value != null ? value : "");
            writeFile(file, memories);
            log.debug("[AgentMemory] 保存: agent={}, userId={}, key={}, value={}", agent, userId, key, value);
        } catch (Exception e) {
            log.warn("[AgentMemory] 保存失败: agent={}, userId={}, key={}, error={}", agent, userId, key, e.getMessage());
        }
    }

    /**
     * 读取一条 Agent 记忆。
     *
     * @return 记忆值；不存在时返回 {@code null}
     */
    public String get(String agent, String userId, String key) {
        if (agent == null || userId == null || key == null) return null;
        try {
            Map<String, String> memories = loadFile(getMemoryFile(agent, userId));
            return memories.get(key);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 删除一条 Agent 记忆。
     */
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
     * 获取该 Agent + 用户的所有记忆，格式化为 LLM 可读文本。
     *
     * <p>返回格式示例：</p>
     * <pre>
     * [用户偏好]
     * - 偏好窗口座位：靠窗
     * - 常用出行路线：北京→上海
     * </pre>
     *
     * @return 格式化文本；无记忆时返回空字符串
     */
    public String getAllFormatted(String agent, String userId) {
        if (agent == null || userId == null) return "";
        try {
            Map<String, String> memories = loadFile(getMemoryFile(agent, userId));
            if (memories.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            sb.append("[用户偏好]\n");
            for (Map.Entry<String, String> entry : memories.entrySet()) {
                String value = entry.getValue();
                if (value != null && !value.isBlank()) {
                    sb.append("- ").append(formatKeyName(entry.getKey())).append("：").append(value).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 检查该用户在该 Agent 下是否有任何记忆。
     */
    public boolean hasMemory(String agent, String userId) {
        if (agent == null || userId == null) return false;
        try {
            return Files.exists(getMemoryFile(agent, userId));
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 文件操作 ====================

    /** 获取记忆文件路径：{basePath}/{userId}/{agent}-memory.md */
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
                // 解析无序列表行：- key: value
                if (line.startsWith(LIST_PREFIX) && line.contains(KV_SEPARATOR)) {
                    String rest = line.substring(LIST_PREFIX.length());
                    int sepIdx = rest.indexOf(KV_SEPARATOR);
                    if (sepIdx > 0) {
                        String key = rest.substring(0, sepIdx).trim();
                        String value = rest.substring(sepIdx + KV_SEPARATOR.length()).trim();
                        if (!key.isEmpty()) {
                            memories.put(key, value);
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

    /** 将记忆写入 Markdown 文件 */
    private void writeFile(Path file, Map<String, String> memories) {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            // 标题
            String agentName = file.getFileName().toString().replace("-memory.md", "");
            sb.append(FILE_TITLE_PREFIX).append(capitalize(agentName)).append(" Agent 用户偏好\n\n");
            // 偏好列表
            for (Map.Entry<String, String> entry : memories.entrySet()) {
                String value = entry.getValue();
                if (value != null && !value.isBlank()) {
                    sb.append(LIST_PREFIX).append(entry.getKey()).append(KV_SEPARATOR).append(value).append("\n");
                }
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[AgentMemory] 写入文件失败: {}, error={}", file, e.getMessage());
        }
    }

    /** 首字母大写 */
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ==================== 键名格式化 ====================

    /** 将 camelCase key 转换为中文可读形式 */
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
}
