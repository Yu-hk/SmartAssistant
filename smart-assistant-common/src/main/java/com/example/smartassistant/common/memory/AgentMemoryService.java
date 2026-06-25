/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Agent 独立记忆服务。
 *
 * <p>为每个 Agent（order/product/general）提供用户粒度的键值记忆存储。
 * 记忆以 Redis key 存储，格式：{@code a2a:memory:{agent}:{userId}:{key}}。</p>
 *
 * <p>写入时机：Agent 工具调用成功后，由 Agent 自身的 MemoryTool 或 AOP 切面触发。</p>
 * <p>读取时机：Agent 处理请求前，由 Controller 将记忆注入 system prompt 的上下文。</p>
 *
 * <p>遵循文章③的核心原则：<strong>"不记什么 > 记什么"</strong>，
 * 只记录可复用的用户偏好和习惯，不记录临时会话细节。</p>
 */
@Component
public class AgentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryService.class);

    private static final String MEMORY_PREFIX = "a2a:memory:";
    /** 默认记忆 TTL：60 天 */
    private static final long DEFAULT_TTL_SECONDS = TimeUnit.DAYS.toSeconds(60);

    private final StringRedisTemplate redisTemplate;

    public AgentMemoryService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 保存一条 Agent 记忆。
     *
     * @param agent   Agent 名称（如 {@code order}）
     * @param userId  用户 ID
     * @param key     记忆键（如 {@code preferWindowSeat}、{@code frequentRoute}）
     * @param value   记忆值
     */
    public void save(String agent, String userId, String key, String value) {
        save(agent, userId, key, value, DEFAULT_TTL_SECONDS);
    }

    /**
     * 保存一条 Agent 记忆（自定义 TTL）。
     *
     * @param agent   Agent 名称
     * @param userId  用户 ID
     * @param key     记忆键
     * @param value   记忆值
     * @param ttlSeconds TTL（秒）
     */
    public void save(String agent, String userId, String key, String value, long ttlSeconds) {
        String redisKey = buildKey(agent, userId, key);
        redisTemplate.opsForValue().set(redisKey, value, ttlSeconds, TimeUnit.SECONDS);
        log.debug("[AgentMemory] 保存: agent={}, userId={}, key={}, ttl={}d",
                agent, userId, key, ttlSeconds / 86400);
    }

    /**
     * 读取一条 Agent 记忆。
     *
     * @return 记忆值；不存在时返回 null
     */
    public String get(String agent, String userId, String key) {
        return redisTemplate.opsForValue().get(buildKey(agent, userId, key));
    }

    /**
     * 删除一条 Agent 记忆。
     */
    public void delete(String agent, String userId, String key) {
        redisTemplate.delete(buildKey(agent, userId, key));
    }

    /**
     * 获取该 Agent + 用户的所有记忆，格式化为 LLM 可读文本。
     *
     * <p>返回格式示例：</p>
     * <pre>
     * [用户偏好]
     * - 常用出发地：北京
     * - 偏好座位：靠窗
     * - 常订车次：G1/G2 次
     * </pre>
     *
     * @return 格式化文本；无记忆时返回空字符串
     */
    public String getAllFormatted(String agent, String userId) {
        Set<String> keys = redisTemplate.keys(buildPrefix(agent, userId) + "*");
        if (keys == null || keys.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("[用户偏好]\n");
        for (String key : keys) {
            String value = redisTemplate.opsForValue().get(key);
            if (value != null && !value.isBlank()) {
                String keyName = key.substring(key.lastIndexOf(':') + 1);
                sb.append("- ").append(formatKeyName(keyName)).append("：").append(value).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 检查该用户在该 Agent 下是否有任何记忆。
     */
    public boolean hasMemory(String agent, String userId) {
        Set<String> keys = redisTemplate.keys(buildPrefix(agent, userId) + "*");
        return keys != null && !keys.isEmpty();
    }

    /** 构建 Redis key：a2a:memory:{agent}:{userId}:{key} */
    private static String buildKey(String agent, String userId, String key) {
        return MEMORY_PREFIX + agent + ":" + userId + ":" + key;
    }

    /** 构建 Redis key 前缀：a2a:memory:{agent}:{userId}: */
    private static String buildPrefix(String agent, String userId) {
        return MEMORY_PREFIX + agent + ":" + userId + ":";
    }

    /** 将 camelCase 或连字符格式的 key 转换为中文可读形式 */
    private static String formatKeyName(String key) {
        // preferWindowSeat → 偏好窗口座位
        // 使用预定义映射
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
