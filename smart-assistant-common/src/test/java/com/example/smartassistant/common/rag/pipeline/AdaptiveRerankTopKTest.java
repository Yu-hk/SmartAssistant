/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.rag.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdaptiveRerankTopK} 单元测试（文章 Q⑦「按意图类型自适应 K」）。
 */
class AdaptiveRerankTopKTest {

    private final AdaptiveRerankTopK resolver = new AdaptiveRerankTopK(3, 5, 8);

    @Test
    @DisplayName("开放式/比较型查询 → 取 maxK(8)")
    void openQueryUsesMaxK() {
        assertEquals(8, resolver.resolve("这两款手机对比一下有什么区别"));
        assertEquals(8, resolver.resolve("推荐几款适合送礼的礼物"));
        assertEquals(8, resolver.resolve("怎么挑选跑步鞋，有哪些攻略"));
    }

    @Test
    @DisplayName("事实型/查数型查询 → 取 minK(3)")
    void factQueryUsesMinK() {
        assertEquals(3, resolver.resolve("这款手机多少钱"));
        assertEquals(3, resolver.resolve("店铺的营业时间电话是多少"));
        assertEquals(3, resolver.resolve("哪款参数更好"));
    }

    @Test
    @DisplayName("普通查询 → 取 defaultK(5)")
    void defaultQueryUsesDefaultK() {
        assertEquals(5, resolver.resolve("我想买个耳机"));
        assertEquals(5, resolver.resolve("帮我看看订单"));
    }

    @Test
    @DisplayName("空/空白查询 → 取 defaultK")
    void blankQueryUsesDefault() {
        assertEquals(5, resolver.resolve(null));
        assertEquals(5, resolver.resolve("   "));
    }

    @Test
    @DisplayName("边界约束：构造时自动归一化为 minK<=defaultK<=maxK")
    void boundsNormalized() {
        // 故意传入乱序/越界边界 (min=10, default=2, max=4)：应自动收敛到 max=4 且三者≤max
        AdaptiveRerankTopK r = new AdaptiveRerankTopK(10, 2, 4);
        assertTrue(r.getMinK() <= r.getDefaultK(), "minK 应 <= defaultK");
        assertTrue(r.getDefaultK() <= r.getMaxK(), "defaultK 应 <= maxK");
        assertEquals(4, r.getMaxK(), "maxK 应保留为 4");
        assertTrue(r.resolve("任意查询") <= r.getMaxK());
    }

    @Test
    @DisplayName("非正数边界应抛异常")
    void nonPositiveThrows() {
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveRerankTopK(0, 5, 8));
    }
}
