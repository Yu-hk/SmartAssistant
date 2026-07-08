/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.model.tier;

import com.example.smartassistant.common.rag.pipeline.QueryComplexityClassifier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * G3 TieredModelRouter 单元测试。
 * covered:
 * <ul>
 *   <li>selectTier 复杂度→档位映射 + 意图覆盖</li>
 *   <li>call 成功路径 + 响应元数据</li>
 *   <li>call 平滑降级（HEAVY→STANDARD 成功 / 全失败抛异常）</li>
 *   <li>call 关闭降级（strict 模式仅尝试选定档位）</li>
 *   <li>Micrometer 指标计数</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TieredModelRouterTest {

    @Mock
    private QueryComplexityClassifier classifier;

    @Mock
    private ChatModel lightModel;

    @Mock
    private ChatModel standardModel;

    @Mock
    private ChatModel heavyModel;

    private TierModelRegistry registry;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        EnumMap<ModelTier, TierModelRegistry.TierModelEntry> m = new EnumMap<>(ModelTier.class);
        m.put(ModelTier.LIGHT, new TierModelRegistry.TierModelEntry(lightModel, "qwen2.5:3b"));
        m.put(ModelTier.STANDARD, new TierModelRegistry.TierModelEntry(standardModel, "deepseek-r1:7b"));
        m.put(ModelTier.HEAVY, new TierModelRegistry.TierModelEntry(heavyModel, "deepseek-chat"));
        registry = new TierModelRegistry(m);
        meterRegistry = new SimpleMeterRegistry();
    }

    private TieredModelRouter router(Map<String, ModelTier> overrides, boolean degradation) {
        return new TieredModelRouter(classifier, registry,
                overrides != null ? overrides : Map.of(), degradation, meterRegistry);
    }

    private static ChatResponse mockResponse(String text) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content(text).build())));
    }

    private static Prompt anyPrompt() {
        return any(Prompt.class);
    }

    // ===== selectTier =====

    @Nested
    @DisplayName("selectTier：复杂度→档位映射")
    class SelectTierTest {

        @Test
        @DisplayName("SIMPLE → LIGHT")
        void simpleToLight() {
            when(classifier.classify("你好")).thenReturn(QueryComplexityClassifier.Complexity.SIMPLE);
            assertSame(ModelTier.LIGHT, router(null, true).selectTier("你好", null));
        }

        @Test
        @DisplayName("MEDIUM → STANDARD")
        void mediumToStandard() {
            when(classifier.classify("查询订单状态")).thenReturn(QueryComplexityClassifier.Complexity.MEDIUM);
            assertSame(ModelTier.STANDARD, router(null, true).selectTier("查询订单状态", null));
        }

        @Test
        @DisplayName("COMPLEX → HEAVY")
        void complexToHeavy() {
            when(classifier.classify("帮我分析退款投诉的完整流程和风险")).thenReturn(QueryComplexityClassifier.Complexity.COMPLEX);
            assertSame(ModelTier.HEAVY, router(null, true).selectTier("帮我分析退款投诉的完整流程和风险", null));
        }

        @Test
        @DisplayName("意图覆盖强制 HEAVY（绕过复杂度分类）")
        void intentOverrideHeavy() {
            Map<String, ModelTier> overrides = Map.of("refund_complaint", ModelTier.HEAVY);
            // 即使查询很短（理论上 SIMPLE），意图覆盖强制 HEAVY
            assertSame(ModelTier.HEAVY, router(overrides, true).selectTier("退款", "refund_complaint"));
            verifyNoInteractions(classifier);
        }

        @Test
        @DisplayName("意图标签为 null 时不触发覆盖")
        void noOverrideWhenNullIntent() {
            when(classifier.classify("查订单")).thenReturn(QueryComplexityClassifier.Complexity.SIMPLE);
            assertSame(ModelTier.LIGHT, router(Map.of("refund_complaint", ModelTier.HEAVY), true)
                    .selectTier("查订单", null));
        }
    }

    // ===== call =====

    @Nested
    @DisplayName("call：核心调用与降级")
    class CallTest {

        @Test
        @DisplayName("HEAVY 档位成功调用，返回选中波次元数据")
        void heavySuccess() {
            when(classifier.classify("复杂分析")).thenReturn(QueryComplexityClassifier.Complexity.COMPLEX);
            when(heavyModel.call(anyPrompt())).thenReturn(mockResponse("深度分析结果"));

            Prompt prompt = new Prompt("复杂分析");
            TierSelection sel = router(null, true).call(prompt, "复杂分析", null);

            assertEquals(ModelTier.HEAVY, sel.requestedTier());
            assertEquals(ModelTier.HEAVY, sel.servedTier());
            assertEquals("deepseek-chat", sel.servedModelName());
            assertFalse(sel.degraded());
            assertTrue(sel.latencyMillis() >= 0);
            assertNotNull(sel.response());
            assertEquals("深度分析结果", sel.response().getResult().getOutput().getText());
        }

        @Test
        @DisplayName("STANDARD 正常调用（MEDIUM 复杂度）")
        void standardSuccess() {
            when(classifier.classify("查询订单")).thenReturn(QueryComplexityClassifier.Complexity.MEDIUM);
            when(standardModel.call(anyPrompt())).thenReturn(mockResponse("订单查询结果"));

            TierSelection sel = router(null, true).call(new Prompt("查询订单"), "查询订单", null);

            assertEquals(ModelTier.STANDARD, sel.servedTier());
            assertFalse(sel.degraded());
        }

        @Test
        @DisplayName("HEAVY 失败 → 降级 STANDARD 成功")
        void degradeFromHeavyToStandard() {
            when(classifier.classify("复杂问题")).thenReturn(QueryComplexityClassifier.Complexity.COMPLEX);
            when(heavyModel.call(anyPrompt())).thenThrow(new RuntimeException("模型 OOM"));
            when(standardModel.call(anyPrompt())).thenReturn(mockResponse("降级回复"));

            TierSelection sel = router(null, true).call(new Prompt("复杂问题"), "复杂问题", null);

            assertEquals(ModelTier.HEAVY, sel.requestedTier());
            assertEquals(ModelTier.STANDARD, sel.servedTier());
            assertEquals("deepseek-r1:7b", sel.servedModelName());
            assertTrue(sel.degraded());
            assertEquals("degraded-from-HEAVY", sel.reason());
            assertEquals(List.of(ModelTier.HEAVY, ModelTier.STANDARD), sel.attemptedTiers());
            assertEquals("降级回复", sel.response().getResult().getOutput().getText());
        }

        @Test
        @DisplayName("HEAVY+STANDARD 均失败 → 降级 LIGHT 成功（完整降级链）")
        void fullDegradationChain() {
            when(classifier.classify("复杂")).thenReturn(QueryComplexityClassifier.Complexity.COMPLEX);
            when(heavyModel.call(anyPrompt())).thenThrow(new RuntimeException("HEAVY 失败"));
            when(standardModel.call(anyPrompt())).thenThrow(new RuntimeException("STANDARD 失败"));
            when(lightModel.call(anyPrompt())).thenReturn(mockResponse("轻量兜底"));

            TierSelection sel = router(null, true).call(new Prompt("复杂"), "复杂", null);

            assertEquals(ModelTier.HEAVY, sel.requestedTier());
            assertEquals(ModelTier.LIGHT, sel.servedTier());
            assertTrue(sel.degraded());
            assertEquals("qwen2.5:3b", sel.servedModelName());
            assertEquals(List.of(ModelTier.HEAVY, ModelTier.STANDARD, ModelTier.LIGHT), sel.attemptedTiers());
            assertEquals("轻量兜底", sel.response().getResult().getOutput().getText());
        }

        @Test
        @DisplayName("所有档位全失败 → ModelTierUnavailableException")
        void allTiersUnavailable() {
            when(classifier.classify("复杂")).thenReturn(QueryComplexityClassifier.Complexity.COMPLEX);
            when(heavyModel.call(anyPrompt())).thenThrow(new RuntimeException("HEAVY fail"));
            when(standardModel.call(anyPrompt())).thenThrow(new RuntimeException("STANDARD fail"));
            when(lightModel.call(anyPrompt())).thenThrow(new RuntimeException("LIGHT fail"));

            TieredModelRouter.ModelTierUnavailableException ex = assertThrows(TieredModelRouter.ModelTierUnavailableException.class,
                    () -> router(null, true).call(new Prompt("复杂"), "复杂", null));

            assertEquals(ModelTier.HEAVY, ex.requested);
            assertEquals(List.of(ModelTier.HEAVY, ModelTier.STANDARD, ModelTier.LIGHT), ex.attempted);
            assertTrue(ex.latencyMillis >= 0);
        }

        @Test
        @DisplayName("关闭降级（degradationEnabled=false）：HEAVY 失败直接抛异常，不尝试 STANDARD")
        void noDegradationStrictMode() {
            when(classifier.classify("复杂")).thenReturn(QueryComplexityClassifier.Complexity.COMPLEX);
            when(heavyModel.call(anyPrompt())).thenThrow(new RuntimeException("HEAVY fail"));

            TieredModelRouter.ModelTierUnavailableException ex = assertThrows(TieredModelRouter.ModelTierUnavailableException.class,
                    () -> router(null, false).call(new Prompt("复杂"), "复杂", null));

            assertEquals(ModelTier.HEAVY, ex.requested);
            // strict 模式只尝试 HEAVY
            assertEquals(List.of(ModelTier.HEAVY), ex.attempted);
            verify(standardModel, never()).call(anyPrompt());
            verify(lightModel, never()).call(anyPrompt());
        }

        @Test
        @DisplayName("STANDARD 档位模型在 registry 中为 null → 跳过")
        void skipNullModelInRegistry() {
            // 构造 registry 时 standard 设置为 null
            EnumMap<ModelTier, TierModelRegistry.TierModelEntry> m = new EnumMap<>(ModelTier.class);
            m.put(ModelTier.LIGHT, new TierModelRegistry.TierModelEntry(lightModel, "qwen2.5:3b"));
            m.put(ModelTier.STANDARD, null);
            m.put(ModelTier.HEAVY, new TierModelRegistry.TierModelEntry(heavyModel, "deepseek-chat"));
            TierModelRegistry partialRegistry = new TierModelRegistry(m);

            when(classifier.classify("中等问题")).thenReturn(QueryComplexityClassifier.Complexity.MEDIUM);
            // 不走 mock standardModel — registry.get(STANDARD) 返回 null
            when(lightModel.call(anyPrompt())).thenReturn(mockResponse("降级到轻量"));

            TieredModelRouter r = new TieredModelRouter(classifier, partialRegistry,
                    Map.of(), true, meterRegistry);
            TierSelection sel = r.call(new Prompt("中等问题"), "中等问题", null);

            // MEDIUM→STANDARD（registry 返回 null），降级→LIGHT 成功
            assertEquals(ModelTier.STANDARD, sel.requestedTier());
            // 注意：selectTier 会把 MEDIUM → STANDARD（这是复杂度映射）
            // 但 registry.get(STANDARD)=null，所以跳过了 STANDARD，降级到 LIGHT
            assertTrue(sel.degraded());
            assertEquals("qwen2.5:3b", sel.servedModelName());
        }

        @Test
        @DisplayName("call 时 query 为空：selectTier 走空查询映射→SIMPLE→LIGHT")
        void callWithNullQuery() {
            when(classifier.classify(any())).thenReturn(QueryComplexityClassifier.Complexity.SIMPLE);
            when(lightModel.call(anyPrompt())).thenReturn(mockResponse("轻量回复"));

            // 传 null query/null intentTag
            TierSelection sel = router(null, true).call(new Prompt("随便问问"), null, null);

            assertEquals(ModelTier.LIGHT, sel.servedTier());
            assertFalse(sel.degraded());
        }
    }

    @Nested
    @DisplayName("Micrometer 指标")
    class MetricsTest {

        @Test
        @DisplayName("成功调用记录档位选择计数")
        void recordsTierCounter() {
            when(classifier.classify("你好")).thenReturn(QueryComplexityClassifier.Complexity.SIMPLE);
            when(lightModel.call(anyPrompt())).thenReturn(mockResponse("你好"));

            double before = meterRegistry.counter("model.tier.selections").count();
            router(null, true).call(new Prompt("你好"), "你好", null);
            double after = meterRegistry.counter("model.tier.selections").count();

            assertEquals(before + 1, after, 0.001);
        }

        @Test
        @DisplayName("降级调用记录降级计数")
        void recordsDegradationCounter() {
            when(classifier.classify("复杂")).thenReturn(QueryComplexityClassifier.Complexity.COMPLEX);
            when(heavyModel.call(anyPrompt())).thenThrow(new RuntimeException("HEAVY 失败"));
            when(standardModel.call(anyPrompt())).thenReturn(mockResponse("降级"));

            double before = meterRegistry.counter("model.tier.degradations").count();
            router(null, true).call(new Prompt("复杂"), "复杂", null);
            double after = meterRegistry.counter("model.tier.degradations").count();

            assertEquals(before + 1, after, 0.001);
        }

        @Test
        @DisplayName("meterRegistry 为 null 时不抛 NPE")
        void nullMeterRegistry() {
            when(classifier.classify("你好")).thenReturn(QueryComplexityClassifier.Complexity.SIMPLE);
            when(lightModel.call(anyPrompt())).thenReturn(mockResponse("你好"));

            TieredModelRouter noMetricsRouter = new TieredModelRouter(
                    classifier, registry, Map.of(), true, null);

            assertDoesNotThrow(() -> noMetricsRouter.call(new Prompt("你好"), "你好", null));
        }
    }
}
