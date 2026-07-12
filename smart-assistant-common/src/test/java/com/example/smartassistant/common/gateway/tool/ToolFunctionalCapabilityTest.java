package com.example.smartassistant.common.gateway.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ToolFunctionalCapability} 单元测试（T1 新增枚举）。
 * <p>
 * 覆盖 {@code fromValue} / {@code isValid} 的精确匹配语义、枚举词表完整性（32 个常量，
 * 设计与文档 §7.1 一致）以及 Javadoc 声明的 WARN-only 宽松校验语义（未知词不抛异常）。
 * </p>
 */
class ToolFunctionalCapabilityTest {

    // ==================== A.1 fromValue() ====================

    @Nested
    @DisplayName("fromValue() — 按值查找枚举")
    class FromValue {

        @Test
        @DisplayName("已知令牌精确匹配返回对应枚举")
        void knownTokenReturnsEnum() {
            assertEquals(ToolFunctionalCapability.GREETING, ToolFunctionalCapability.fromValue("greeting"));
            assertEquals(ToolFunctionalCapability.ORDER_QUERY, ToolFunctionalCapability.fromValue("order-query"));
            assertEquals(ToolFunctionalCapability.ORDER_REFUND, ToolFunctionalCapability.fromValue("order-refund"));
            assertEquals(ToolFunctionalCapability.SQL_QUERY, ToolFunctionalCapability.fromValue("sql-query"));
        }

        @Test
        @DisplayName("null 返回 null（不抛异常）")
        void nullReturnsNull() {
            assertNull(ToolFunctionalCapability.fromValue(null));
        }

        @Test
        @DisplayName("未知令牌返回 null")
        void unknownTokenReturnsNull() {
            assertNull(ToolFunctionalCapability.fromValue("foo-bar"));
            assertNull(ToolFunctionalCapability.fromValue("not-a-real-capability"));
        }

        @Test
        @DisplayName("大小写不匹配返回 null（精确匹配，非大小写无关）")
        void caseMismatchReturnsNull() {
            assertNull(ToolFunctionalCapability.fromValue("Greeting"));
            assertNull(ToolFunctionalCapability.fromValue("ORDER-QUERY"));
            assertNull(ToolFunctionalCapability.fromValue("Sql-Query"));
        }
    }

    // ==================== A.2 isValid() ====================

    @Nested
    @DisplayName("isValid() — 校验已知令牌")
    class IsValid {

        @Test
        @DisplayName("已知令牌返回 true")
        void knownTokenReturnsTrue() {
            assertTrue(ToolFunctionalCapability.isValid("greeting"));
            assertTrue(ToolFunctionalCapability.isValid("product-query"));
            assertTrue(ToolFunctionalCapability.isValid("order-refund"));
            assertTrue(ToolFunctionalCapability.isValid("sql-query"));
        }

        @Test
        @DisplayName("null / 未知词 / 大小写变体返回 false")
        void invalidReturnsFalse() {
            assertFalse(ToolFunctionalCapability.isValid(null));
            assertFalse(ToolFunctionalCapability.isValid("foo-bar"));
            assertFalse(ToolFunctionalCapability.isValid("Greeting"));
            assertFalse(ToolFunctionalCapability.isValid("ORDER-REFUND"));
        }
    }

    // ==================== A.3 枚举词表完整性 ====================

    @Nested
    @DisplayName("枚举词表完整性（设计文档 §7.1）")
    class VocabularyIntegrity {

        @Test
        @DisplayName("枚举常量总数应为 32")
        void shouldHaveExactly32Constants() {
            assertEquals(32, ToolFunctionalCapability.values().length);
        }

        @Test
        @DisplayName("代表性子集 getValue() 与设计词表一致")
        void representativeSubsetValuesMatchSpec() {
            assertEquals("greeting", ToolFunctionalCapability.GREETING.getValue());
            assertEquals("math-calculate", ToolFunctionalCapability.MATH_CALCULATE.getValue());
            assertEquals("product-query", ToolFunctionalCapability.PRODUCT_QUERY.getValue());
            assertEquals("order-refund", ToolFunctionalCapability.ORDER_REFUND.getValue());
            assertEquals("sql-query", ToolFunctionalCapability.SQL_QUERY.getValue());
            assertEquals("knowledge-retrieve", ToolFunctionalCapability.KNOWLEDGE_RETRIEVE.getValue());
            assertEquals("weather-query", ToolFunctionalCapability.WEATHER_QUERY.getValue());
        }

        @Test
        @DisplayName("全部 32 个常量 value 非空、非空白且互不重复")
        void allValuesNonNullAndDistinct() {
            Set<String> seen = new HashSet<>();
            for (ToolFunctionalCapability cap : ToolFunctionalCapability.values()) {
                String value = cap.getValue();
                assertNotNull(value, "枚举常量的 value 不应为 null");
                assertFalse(value.isBlank(), "枚举常量的 value 不应为空串");
                assertTrue(seen.add(value), "发现重复 value: " + value);
            }
            assertEquals(32, seen.size());
        }
    }

    // ==================== A.4 WARN-only 宽松语义 ====================

    @Nested
    @DisplayName("WARN-only 语义（v1 宽松校验）")
    class WarnOnlySemantics {

        @Test
        @DisplayName("未知自定义词不应抛异常（isValid / fromValue 均安全返回）")
        void unknownCustomTokenDoesNotThrow() {
            assertDoesNotThrow(() -> {
                assertFalse(ToolFunctionalCapability.isValid("some-custom-business-token"));
                assertNull(ToolFunctionalCapability.fromValue("some-custom-business-token"));
            });
        }
    }
}
