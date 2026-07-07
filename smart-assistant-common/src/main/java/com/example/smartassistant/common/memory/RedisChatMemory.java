/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的分布式 {@link ChatMemory} 实现（生产多实例部署推荐使用）。
 *
 * <h2>设计要点</h2>
 * <ul>
 *   <li>使用 {@link StringRedisTemplate} 存储 JSON 字符串，避免 JDK 原生序列化；</li>
 *   <li>通过 {@link MessageCodec} 做 <b>Message 接口多态类型安全</b> 编解码，
 *       规避 Jackson 直接序列化 {@code List<Message>} 时子类类型擦除导致的数据损坏；</li>
 *   <li>有界环形缓冲：超过 {@code maxMessages} 淘汰最旧消息，与 {@link InMemoryChatMemory} 行为一致；</li>
 *   <li>可选 TTL：默认 0（不过期，由业务侧清理），可传 {@code ttl} 实现滑动过期。</li>
 * </ul>
 *
 * <p><b>与 {@link InMemoryChatMemory} 的关系：</b>两者实现同一 {@link ChatMemory} 抽象，
 * Bean 注册时通过 profile / 配置项二选一，本类不强制替换内存实现。</p>
 */
public class RedisChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemory.class);

    /** Redis key 前缀（与项目 a2a:* 命名规范一致）。 */
    private static final String KEY_PREFIX = "a2a:chat:memory:";
    private static final String FIELD_TYPE = "type";

    private final StringRedisTemplate redisTemplate;
    private final int maxMessages;
    private final long ttlSeconds;

    public RedisChatMemory(StringRedisTemplate redisTemplate) {
        this(redisTemplate, 100, 0);
    }

    public RedisChatMemory(StringRedisTemplate redisTemplate, int maxMessages, long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.maxMessages = Math.max(1, maxMessages);
        this.ttlSeconds = Math.max(0, ttlSeconds);
    }

    private String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }

    @Override
    public void add(String conversationId, Message message) {
        if (conversationId == null || message == null) {
            return;
        }
        String k = key(conversationId);
        List<Message> list = get(conversationId, 0);
        list.add(message);
        while (list.size() > maxMessages) {
            list.remove(0);
        }
        String json = MessageCodec.encodeList(list);
        if (ttlSeconds > 0) {
            redisTemplate.opsForValue().set(k, json, ttlSeconds, TimeUnit.SECONDS);
        } else {
            redisTemplate.opsForValue().set(k, json);
        }
        log.debug("[RedisChatMemory] add: conv={}, size={}", conversationId, list.size());
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        if (conversationId == null) {
            return List.of();
        }
        String json = redisTemplate.opsForValue().get(key(conversationId));
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<Message> all = MessageCodec.decodeList(json);
        if (lastN <= 0 || lastN >= all.size()) {
            return List.copyOf(all);
        }
        return List.copyOf(all.subList(all.size() - lastN, all.size()));
    }

    @Override
    public void clear(String conversationId) {
        if (conversationId == null) {
            return;
        }
        redisTemplate.delete(key(conversationId));
    }
}
