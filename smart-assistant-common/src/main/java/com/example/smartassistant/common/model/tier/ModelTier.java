/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.model.tier;

/**
 * 模型档位（Tier）——对标文章④「多模型路由降级 Tier1/2/3」。
 *
 * <p>档位按能力/成本从低到高排序：{@code LIGHT → STANDARD → HEAVY}。
 * 数字 {@code order} 用于构建降级链：档位越低越稳定、越便宜，{@link #LIGHT} 始终作为最终兜底。</p>
 */
public enum ModelTier {

    /** Tier1 轻量模型：本地小模型（如 qwen2.5:3b），低延迟低成本，用于简单问答与兜底。 */
    LIGHT(1, "light", "本地轻量模型"),

    /** Tier2 标准模型：本地主力（如 deepseek-r1:7b），兼顾能力与成本，处理中等复杂度。 */
    STANDARD(2, "standard", "本地标准模型"),

    /** Tier3 强模型：云端大模型（如 DeepSeek/GPT），高能力高成本，处理复杂推理。 */
    HEAVY(3, "heavy", "强模型");

    private final int order;
    private final String code;
    private final String description;

    ModelTier(int order, String code, String description) {
        this.order = order;
        this.code = code;
        this.description = description;
    }

    public int getOrder() {
        return order;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 降级到相邻更低档位；{@link #LIGHT} 为最低档，返回自身。
     */
    public ModelTier lower() {
        return switch (this) {
            case HEAVY -> STANDARD;
            case STANDARD -> LIGHT;
            case LIGHT -> LIGHT;
        };
    }
}
