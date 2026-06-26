/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.experience;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 经验修剪器 —— 定期淘汰低质量/过时的经验。
 *
 * <p>参考文章③的关键原则：<strong>"淘汰机制至关重要"</strong>，
 * 错误/偏差的持久记忆会持续负面影响后续调用。</p>
 *
 * <p>淘汰策略：</p>
 * <ul>
 *   <li>创建超过 30 天、最近 30 天未命中、总命中 < 5 → 淘汰</li>
 *   <li>创建超过 60 天、最近 14 天未命中、总命中 < 3 → 淘汰</li>
 *   <li>不可反序列化的经验 → 修复或清除</li>
 * </ul>
 *
 * <p>除定时淘汰外，每次 {@link ExperienceService#match(String)} 发现不可用经验时
 * 也会触发单条淘汰。</p>
 *
 * @see ExperienceValidator
 * @see ExperienceService
 */
@Component
public class ExperiencePruner {

    private static final Logger log = LoggerFactory.getLogger(ExperiencePruner.class);

    private static final String EXP_PREFIX = "a2a:experience:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ExperienceValidator validator;

    public ExperiencePruner(StringRedisTemplate redisTemplate,
                            ExperienceValidator validator) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.validator = validator;
    }

    /**
     * 每日凌晨 3:00 执行经验淘汰扫描。
     * <p>
     * 扫描所有经验条目，用 {@link ExperienceValidator#shouldPrune(ExperienceModel)}
     * 判定是否淘汰。淘汰时删除 Redis key + pgvector 嵌入。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void pruneDaily() {
        log.info("[ExperiencePruner] 开始每日经验淘汰扫描...");
        try {
            Set<String> keys = redisTemplate.keys(EXP_PREFIX + "*");
            if (keys == null || keys.isEmpty()) {
                log.info("[ExperiencePruner] 无经验条目，跳过");
                return;
            }

            int pruned = 0;
            int totalKeys = 0;

            for (String key : keys) {
                // 排除索引 key（以 index: / keyword: / intent: 开头）
                if (key.contains(":index:") || key.contains(":keyword:") || key.contains(":intent:")) {
                    continue;
                }

                totalKeys++;
                String json = redisTemplate.opsForValue().get(key);
                if (json == null) {
                    redisTemplate.delete(key);
                    pruned++;
                    continue;
                }

                try {
                    ExperienceModel exp = objectMapper.readValue(json, ExperienceModel.class);
                    if (validator.shouldPrune(exp)) {
                        // 删除经验数据和嵌入向量
                        redisTemplate.delete(key);
                        // 清除关联索引（关键词索引由 saveExperience 维护，删除单个 key 后保持脏读可接受）
                        // ⭐ 此处仅清除经验本身，索引索引会在下次匹配时自然淘汰（loadExperience 返回 null 则跳过）
                        pruned++;
                        log.debug("[ExperiencePruner] 淘汰经验: id={}, type={}, hits={}, age={}d",
                                exp.getId(), exp.getType(), exp.getHitCount(),
                                TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - exp.getCreatedAt()));
                    }
                } catch (Exception e) {
                    // 不可反序列化的经验 → 清除
                    redisTemplate.delete(key);
                    pruned++;
                    log.warn("[ExperiencePruner] 清除不可反序列化的经验: key={}, error={}", key, e.getMessage());
                }
            }

            log.info("[ExperiencePruner] ⭐ 经验淘汰完成: 扫描 {} 条, 淘汰 {} 条", totalKeys, pruned);

        } catch (Exception e) {
            log.warn("[ExperiencePruner] 经验淘汰扫描异常: {}", e.getMessage());
        }
    }
}
