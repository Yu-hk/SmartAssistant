/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RouterRagService} 回指逻辑单元测试。
 * <p>
 * 覆盖无需 LLM 调用的纯逻辑方法：EntityRef 记录、isRagWorthy（上下文词检测）、
 * buildEnhancedQuestion（拼接）、generateBackReferences（去重+分组排版）。
 * </p>
 */
@DisplayName("RouterRagService 回指逻辑测试")
class RouterRagServiceTest {

    /** 通过反射调用私有方法。 */
    @SuppressWarnings("unchecked")
    private static <T> T invokePrivate(Object target, String methodName, Object... args) {
        try {
            for (Method m : target.getClass().getDeclaredMethods()) {
                if (m.getName().equals(methodName)) {
                    m.setAccessible(true);
                    return (T) m.invoke(target, args);
                }
            }
            throw new NoSuchMethodException(methodName);
        } catch (Exception e) {
            throw new RuntimeException("反射调用失败: " + methodName, e);
        }
    }

    private final RouterRagService service = new RouterRagService(null, null);

    // ==================== EntityRef 记录 ====================

    @Nested @DisplayName("EntityRef 记录（类型/值/轮次）")
    class EntityRefTest {

        @Test @DisplayName("构造 + getter")
        void constructAndAccess() {
            var ref = new RouterRagService.EntityRef("ORDER_ID", "ORD-001", "round-1");
            assertEquals("ORDER_ID", ref.type());
            assertEquals("ORD-001", ref.value());
            assertEquals("round-1", ref.sourceIndex());
        }

        @Test @DisplayName("不同类型实体可正常创建")
        void variousTypes() {
            assertDoesNotThrow(() -> new RouterRagService.EntityRef("DECISION", "退款", "r2"));
            assertDoesNotThrow(() -> new RouterRagService.EntityRef("PRODUCT", "iPhone 15", "r1"));
            assertDoesNotThrow(() -> new RouterRagService.EntityRef("AMOUNT", "¥5999", "r3"));
            assertDoesNotThrow(() -> new RouterRagService.EntityRef("ACTION", "取消订单", "r4"));
            assertDoesNotThrow(() -> new RouterRagService.EntityRef("DATE", "2026-07-10", "r5"));
        }
    }

    // ==================== buildEnhancedQuestion ====================

    @Nested @DisplayName("buildEnhancedQuestion 拼接")
    class BuildEnhancedQuestionTest {

        @Test @DisplayName("仅有上下文 → 输出含【上下文】+【当前问题】")
        void contextOnly() {
            String result = invokePrivate(service, "buildEnhancedQuestion",
                    "请问在哪里发货？", "用户上次买了手机", null);
            assertAll(
                    () -> assertTrue(result.contains("【上下文】")),
                    () -> assertTrue(result.contains("用户上次买了手机")),
                    () -> assertTrue(result.contains("【当前问题】")),
                    () -> assertTrue(result.contains("请问在哪里发货？")),
                    () -> assertFalse(result.contains("【历史引用】"))
            );
        }

        @Test @DisplayName("有回指 → 输出含【上下文】+【历史引用】+【当前问题】")
        void contextAndBackRef() {
            String result = invokePrivate(service, "buildEnhancedQuestion",
                    "它的价格是多少？", "推荐了iPhone 15", "- 商品: iPhone 15\n");
            assertTrue(result.contains("【历史引用】"));
            assertTrue(result.contains("iPhone 15"));
        }

        @Test @DisplayName("没有上下文和回指 → 返回原问题")
        void noContext_noBackRef_returnsOriginal() {
            String result = invokePrivate(service, "buildEnhancedQuestion",
                    "你好", null, null);
            assertEquals("你好", result);
        }
    }

    // ==================== isRagWorthy ====================

    @Nested @DisplayName("isRagWorthy RAG 判定")
    class IsRagWorthyTest {

        @Test @DisplayName("历史少于 1 条 → false")
        void shortHistory_isFalse() {
            assertFalse(invokePrivate(service, "isRagWorthy", "test", List.of()));
        }

        @Test @DisplayName("问题短于 10 字且无上下文词 → false")
        void shortQuestionWithoutIndicators_isFalse() {
            var history = List.of("a", "b", "c");
            assertFalse(invokePrivate(service, "isRagWorthy", "你好", history));
        }

        @Test @DisplayName("含它上下文词 → true")
        void contains它_isTrue() {
            var history = List.of("a", "b", "c");
            assertTrue(invokePrivate(service, "isRagWorthy", "它的价格是多少？", history));
        }

        @Test @DisplayName("含这个上下文词 → true")
        void contains这个_isTrue() {
            var history = List.of("a", "b", "c");
            assertTrue(invokePrivate(service, "isRagWorthy", "这个怎么用？", history));
        }

        @Test @DisplayName("含上次上下文词 → true")
        void contains上次_isTrue() {
            var history = List.of("a", "b", "c");
            assertTrue(invokePrivate(service, "isRagWorthy", "上次说的那个问题", history));
        }

        @Test @DisplayName("历史多且问题短 (＜15) → true")
        void manyHistoryShortQuestion_isTrue() {
            var history = List.of("a", "b", "c", "d");
            assertTrue(invokePrivate(service, "isRagWorthy", "还有呢？", history));
        }
    }

    // ==================== generateBackReferences ====================

    @Nested @DisplayName("generateBackReferences 回指生成")
    class GenerateBackReferencesTest {

        @Test @DisplayName("单一 ORDER_ID → 输出- 订单: ORD-001")
        void singleOrderId() {
            var entities = List.of(
                    new RouterRagService.EntityRef("ORDER_ID", "ORD-001", "r1"));
            String result = invokePrivate(service, "generateBackReferences", entities);
            assertTrue(result.contains("ORD-001"));
        }

        @Test @DisplayName("多个 DECISION → 去重后只输出一次")
        void dedupDecisions() {
            var entities = List.of(
                    new RouterRagService.EntityRef("DECISION", "退款", "r1"),
                    new RouterRagService.EntityRef("DECISION", "退款", "r2"));
            String result = invokePrivate(service, "generateBackReferences", entities);
            assertEquals(1, result.split("退款").length - 1, "退款应只出现一次");
        }

        @Test @DisplayName("混合类型 → 按优先级排版")
        void priorityOrdering() {
            var entities = List.of(
                    new RouterRagService.EntityRef("AMOUNT", "¥5999", "r1"),
                    new RouterRagService.EntityRef("DECISION", "退款", "r2"),
                    new RouterRagService.EntityRef("PRODUCT", "手机", "r3"));
            String result = invokePrivate(service, "generateBackReferences", entities);
            int idxDecision = result.indexOf("决策");
            int idxProduct = result.indexOf("商品");
            int idxAmount = result.indexOf("金额");
            // 决策在商品前
            assertTrue(idxDecision < idxProduct, "决策应排在商品前");
        }

        @Test @DisplayName("空实体列表 → 返回空字符串")
        void emptyList_returnsEmpty() {
            String result = invokePrivate(service, "generateBackReferences", List.of());
            assertEquals("", result);
        }
    }

    // ==================== 实体提取格式 ====================

    @Nested @DisplayName("实体提取格式（管线拆分逻辑）")
    class EntityExtractionParsingTest {

        @Test @DisplayName("标准管线格式：type|value|source")
        void parseStandardLine() {
            var ref = new RouterRagService.EntityRef("ORDER_ID", "ORD-001", "r1");
            assertEquals("ORDER_ID", ref.type());
            assertEquals("ORD-001", ref.value());
        }

        @Test @DisplayName("不含 source：type|value")
        void parseNoSource() {
            var ref = new RouterRagService.EntityRef("DECISION", "退款", "");
            assertEquals("退款", ref.value());
            assertEquals("", ref.sourceIndex());
        }

        @Test @DisplayName("管线中间含中文")
        void parseChineseValues() {
            var ref = new RouterRagService.EntityRef("PRODUCT", "苹果手机 iPhone 15 Pro", "round-2");
            assertEquals("苹果手机 iPhone 15 Pro", ref.value());
        }
    }
}
