/*
 * Copyright (c) 2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.experience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 经验验证器 —— 在经验召回后、使用前执行验证。
 *
 * <p>参考 Claude Code Memory 方案的核心设计理念：
 * <strong>"不可错记 > 不可漏记"</strong> 和 <strong>"信任当前观察 > 信任记忆"</strong>。
 * 召回经验后必须验证其时效性和可靠度，而非盲目使用。</p>
 *
 * <p>验证维度：</p>
 * <ul>
 *   <li><b>时效性</b>：超过 {@link #STALE_THRESHOLD_HOURS} 小时未命中的经验标记为"可能过时"</li>
 *   <li><b>可靠度</b>：命中次数低于 {@link #MIN_HITS_FOR_TRUST} 的经验标记为"置信度较低"</li>
 *   <li><b>新鲜度</b>：创建后从未命中的经验标记为"未经验证"</li>
 * </ul>
 *
 * @see ExperienceService
 * @see ExperiencePruner
 */
@Component
public class ExperienceValidator {

    private static final Logger log = LoggerFactory.getLogger(ExperienceValidator.class);

    /** 经验过时阈值（小时）：超过此时间未命中视为可能过时 */
    static final long STALE_THRESHOLD_HOURS = 24;

    /** 经验可信的最小命中次数 */
    static final int MIN_HITS_FOR_TRUST = 3;

    /**
     * 验证单个经验，返回警告列表。
     *
     * @param exp 待验证的经验
     * @return 非空警告列表中的每条代表一个验证发现的问题；空列表表示无警告
     */
    public List<String> validate(ExperienceModel exp) {
        List<String> warnings = new ArrayList<>();
        if (exp == null) return warnings;

        long now = System.currentTimeMillis();

        // 1) 时效性检查：超过 24h 未命中 → 可能过时
        if (exp.getLastHitAt() > 0) {
            long hoursSinceLastHit = TimeUnit.MILLISECONDS.toHours(now - exp.getLastHitAt());
            if (hoursSinceLastHit >= STALE_THRESHOLD_HOURS) {
                warnings.add("该经验已 " + hoursSinceLastHit + " 小时未命中，信息可能已过时");
            }
        }

        // 2) 可靠度检查：命中次数过低
        if (exp.getHitCount() < MIN_HITS_FOR_TRUST) {
            warnings.add("该经验命中次数较少（" + exp.getHitCount() + "次），置信度可能不足");
        }

        // 3) 新鲜度检查：创建后从未命中
        if (exp.getLastHitAt() == 0 && exp.getHitCount() == 0) {
            warnings.add("该经验创建后从未被使用验证，请谨慎依赖");
        }

        return warnings;
    }

    /**
     * 根据验证结果返回经验是否"安全可用"。
     * <p>无警告 → 完全可信；有非严重警告 → 可用但需标记；严重问题 → 不可用。</p>
     */
    public boolean isUsable(ExperienceModel exp) {
        List<String> warnings = validate(exp);
        // 当前规则：只要经验存在且非空，均视为可用（仅附加警告，不阻止使用）
        // 这是"不记什么 > 记什么"策略中"信任当前观察"的体现
        // 后续可引入更严格的策略（如 hitCount==0 时降级）
        return exp != null;
    }

    /**
     * 判断经验是否应被淘汰（供 {@link ExperiencePruner} 使用）。
     */
    public boolean shouldPrune(ExperienceModel exp) {
        if (exp == null) return true;

        long now = System.currentTimeMillis();
        long ageDays = TimeUnit.MILLISECONDS.toDays(now - exp.getCreatedAt());
        long idleDays = exp.getLastHitAt() > 0
                ? TimeUnit.MILLISECONDS.toDays(now - exp.getLastHitAt())
                : ageDays;

        // 淘汰条件：创建超过 30 天 且 最近 30 天未命中 且 总命中 < 5
        boolean oldAndUnused = ageDays >= 30 && idleDays >= 30 && exp.getHitCount() < 5;
        // 淘汰条件：创建超过 60 天 且 最近 14 天未命中 且 总命中 < 3
        boolean veryOldAndRare = ageDays >= 60 && idleDays >= 14 && exp.getHitCount() < 3;

        return oldAndUnused || veryOldAndRare;
    }
}
