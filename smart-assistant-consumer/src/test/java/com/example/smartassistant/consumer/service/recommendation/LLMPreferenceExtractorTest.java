/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.common.rag.advisor.AiChatService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ⭐ P2-C：验证隐藏关键信息（潜在需求/隐性信号）的正则探测 {@code detectLatentSignals}。
 * 纯正则实现，不依赖 LLM / tokenizer，用 mock 构造即可触发真实逻辑。
 */
class LLMPreferenceExtractorTest {

    private final LLMPreferenceExtractor extractor = new LLMPreferenceExtractor(
            mock(AiChatService.class), mock(ChatModel.class), mock(com.example.smartassistant.common.tokenizer.ChineseTokenizer.class));

    @Test
    void detectLatentSignals_findsMultipleCategories() {
        List<String> hits = extractor.detectLatentSignals("我妈年纪大了，经常出差，这次是公司团建采购");
        assertTrue(hits.contains("家庭/适老场景"), "应识别适老场景, 实际=" + hits);
        assertTrue(hits.contains("经常出差"), "应识别经常出差, 实际=" + hits);
        assertTrue(hits.contains("企业采购(B2B)"), "应识别企业采购, 实际=" + hits);
    }

    @Test
    void detectLatentSignals_handlesCalmStatements() {
        // 平静陈述句，无情绪关键词，也应命中
        List<String> hits = extractor.detectLatentSignals("怀孕了，想看看适合孕妇的路线");
        assertTrue(hits.contains("特殊健康期"));
    }

    @Test
    void detectLatentSignals_dedupAndEmpty() {
        // 同一标签不应重复
        List<String> hits = extractor.detectLatentSignals("带娃出门，亲子游，孩子喜欢动物");
        assertEquals(1, hits.stream().filter(s -> s.equals("亲子场景")).count());
        // 空白无命中
        assertTrue(extractor.detectLatentSignals("").isEmpty());
        assertTrue(extractor.detectLatentSignals(null).isEmpty());
    }

    @Test
    void detectLatentSignals_timeAndCompetitor() {
        List<String> hits = extractor.detectLatentSignals("之前一直在别家买，这次赶时间要尽快发货");
        assertTrue(hits.contains("竞品来源"), "应识别竞品来源, 实际=" + hits);
        assertTrue(hits.contains("时效敏感"), "应识别时效敏感, 实际=" + hits);
    }
}
