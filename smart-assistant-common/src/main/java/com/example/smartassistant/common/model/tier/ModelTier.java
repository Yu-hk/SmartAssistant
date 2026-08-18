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

    /** Tier1 轻量模型：低延迟模型（默认 DeepSeek V4 Flash），用于简单问答与兜底。 */
    LIGHT(1, "light", "低延迟模型"),

    /** Tier2 标准模型：兼顾能力与成本（默认 DeepSeek V4 Flash），处理中等复杂度。 */
    STANDARD(2, "standard", "标准模型"),

    /** Tier3 强模型：高能力模型（默认 DeepSeek V4 Pro），处理复杂推理。 */
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
