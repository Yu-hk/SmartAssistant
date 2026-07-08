/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.model.tier;

import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 档位模型注册表——持有每个 {@link ModelTier} 对应的 {@link ChatModel} 及其模型名。
 *
 * <p>降级链 = 从请求档位沿 {@link ModelTier#lower()} 一直降到 {@link ModelTier#LIGHT}，
 * 保证本地轻量模型永远是最终兜底，请求不会因单一档位故障而整体失败。</p>
 */
public class TierModelRegistry {

    /** 档位条目：模型实例 + 模型名（用于可观测性）。 */
    public record TierModelEntry(ChatModel model, String modelName) {
    }

    private final Map<ModelTier, TierModelEntry> entries;

    public TierModelRegistry(Map<ModelTier, TierModelEntry> entries) {
        this.entries = new EnumMap<>(entries);
    }

    /** 获取指定档位的 ChatModel（无则返回 null）。 */
    public ChatModel get(ModelTier tier) {
        TierModelEntry e = entries.get(tier);
        return e != null ? e.model() : null;
    }

    /** 获取指定档位的模型名（无则返回档位 code）。 */
    public String modelName(ModelTier tier) {
        TierModelEntry e = entries.get(tier);
        return e != null ? e.modelName() : (tier != null ? tier.getCode() : "unknown");
    }

    /** 该档位是否配置了可用模型。 */
    public boolean has(ModelTier tier) {
        return entries.get(tier) != null;
    }

    /** 从指定档位到 LIGHT 的完整降级链（含起止，自动去重）。 */
    public List<ModelTier> fallbackChain(ModelTier from) {
        List<ModelTier> chain = new ArrayList<>();
        ModelTier cur = from;
        while (cur != null && !chain.contains(cur)) {
            chain.add(cur);
            if (cur == ModelTier.LIGHT) {
                break;
            }
            cur = cur.lower();
        }
        return chain;
    }
}
