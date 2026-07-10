package com.example.smartassistant.toolregistry.service;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ToolManifestValidator} 单元测试。
 * <p>
 * 验证 capabilities 和 outputSchema 的校验逻辑：
 * <ul>
 *   <li>capabilities 为空/null → 填默认 ["unknown"]</li>
 *   <li>capabilities 含非法值 → WARN 不阻断</li>
 *   <li>outputSchema 合法 JSON → 通过</li>
 *   <li>outputSchema 非法 JSON → WARN 不阻断</li>
 * </ul>
 * </p>
 */
class ToolManifestValidatorTest {

    private ToolManifestValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ToolManifestValidator(new ObjectMapper());
    }

    private ToolDefinition.ToolDefinitionBuilder baseBuilder() {
        return ToolDefinition.builder()
                .name("test-tool")
                .description("测试工具");
    }

    // ==================== capabilities 校验 ====================

    @Nested
    @DisplayName("capabilities 校验")
    class CapabilitiesValidation {

        @Test
        @DisplayName("capabilities 为 null 时填充默认值 [unknown]")
        void nullCapabilitiesShouldBeFilledWithDefault() {
            ToolDefinition def = baseBuilder().capabilities(null).build();

            validator.validate(def);

            assertArrayEquals(new String[]{"unknown"}, def.getCapabilities());
        }

        @Test
        @DisplayName("capabilities 为空数组时填充默认值 [unknown]")
        void emptyCapabilitiesShouldBeFilledWithDefault() {
            ToolDefinition def = baseBuilder().capabilities(new String[]{}).build();

            validator.validate(def);

            assertArrayEquals(new String[]{"unknown"}, def.getCapabilities());
        }

        @Test
        @DisplayName("capabilities 含非法值时不阻断（仅 WARN）")
        void invalidCapabilitiesShouldNotBlock() {
            ToolDefinition def = baseBuilder()
                    .capabilities(new String[]{"read-only", "invalid-cap"})
                    .build();
            String[] original = def.getCapabilities().clone();

            validator.validate(def);

            // 不阻断：capabilities 未被修改
            assertArrayEquals(original, def.getCapabilities());
        }

        @Test
        @DisplayName("capabilities 全部为合法值时不修改")
        void validCapabilitiesShouldNotBeModified() {
            ToolDefinition def = baseBuilder()
                    .capabilities(new String[]{"read-only", "mutate-state", "payment"})
                    .build();

            validator.validate(def);

            assertArrayEquals(new String[]{"read-only", "mutate-state", "payment"}, def.getCapabilities());
        }

        @Test
        @DisplayName("capabilities 仅含 unknown 合法值时不修改")
        void unknownCapabilityShouldBeValid() {
            ToolDefinition def = baseBuilder()
                    .capabilities(new String[]{"unknown"})
                    .build();

            validator.validate(def);

            assertArrayEquals(new String[]{"unknown"}, def.getCapabilities());
        }

        @Test
        @DisplayName("capabilities 含全部 7 种预定义值时不修改")
        void allPredefinedCapabilitiesShouldBeValid() {
            String[] allCaps = {
                    "read-only", "mutate-state", "network-call",
                    "payment", "data-access", "ai-inference", "unknown"
            };
            ToolDefinition def = baseBuilder().capabilities(allCaps).build();

            validator.validate(def);

            assertArrayEquals(allCaps, def.getCapabilities());
        }

        @Test
        @DisplayName("混合合法和非法值时不阻断、不修改")
        void mixedValidInvalidCapabilitiesShouldNotBlock() {
            ToolDefinition def = baseBuilder()
                    .capabilities(new String[]{"read-only", "fake-cap", "payment", "not-real"})
                    .build();
            String[] original = def.getCapabilities().clone();

            validator.validate(def);

            // 含非法值但不应阻断，capabilities 保持原样
            assertArrayEquals(original, def.getCapabilities());
        }
    }

    // ==================== outputSchema 校验 ====================

    @Nested
    @DisplayName("outputSchema 校验")
    class OutputSchemaValidation {

        @Test
        @DisplayName("outputSchema 为 null 时跳过校验")
        void nullOutputSchemaShouldBeSkipped() {
            ToolDefinition def = baseBuilder().outputSchema(null).build();

            assertDoesNotThrow(() -> validator.validate(def));
        }

        @Test
        @DisplayName("outputSchema 为空字符串时跳过校验")
        void blankOutputSchemaShouldBeSkipped() {
            ToolDefinition def = baseBuilder().outputSchema("   ").build();

            assertDoesNotThrow(() -> validator.validate(def));
        }

        @Test
        @DisplayName("outputSchema 为合法 JSON 时不抛异常")
        void validJsonOutputSchemaShouldPass() {
            ToolDefinition def = baseBuilder()
                    .outputSchema("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}")
                    .build();

            assertDoesNotThrow(() -> validator.validate(def));
        }

        @Test
        @DisplayName("outputSchema 为简单合法 JSON 时不抛异常")
        void simpleValidJsonOutputSchemaShouldPass() {
            ToolDefinition def = baseBuilder()
                    .outputSchema("{\"type\":\"string\"}")
                    .build();

            assertDoesNotThrow(() -> validator.validate(def));
        }

        @Test
        @DisplayName("outputSchema 为数组型合法 JSON 时不抛异常")
        void arrayValidJsonOutputSchemaShouldPass() {
            ToolDefinition def = baseBuilder()
                    .outputSchema("[1, 2, 3]")
                    .build();

            assertDoesNotThrow(() -> validator.validate(def));
        }

        @Test
        @DisplayName("outputSchema 为非法 JSON 时仅 WARN 不阻断")
        void invalidJsonOutputSchemaShouldNotBlock() {
            ToolDefinition def = baseBuilder()
                    .outputSchema("{invalid json!!!")
                    .build();

            assertDoesNotThrow(() -> validator.validate(def));
        }

        @Test
        @DisplayName("outputSchema 为半截 JSON 时不阻断")
        void truncatedJsonOutputSchemaShouldNotBlock() {
            ToolDefinition def = baseBuilder()
                    .outputSchema("{\"type\":\"string\"")
                    .build();

            assertDoesNotThrow(() -> validator.validate(def));
        }
    }

    // ==================== 组合校验 ====================

    @Nested
    @DisplayName("组合校验（capabilities + outputSchema）")
    class CombinedValidation {

        @Test
        @DisplayName("空 capabilities + 非法 JSON outputSchema → 填默认 + 不阻断")
        void emptyCapabilitiesAndInvalidJsonShouldFillDefaultAndNotBlock() {
            ToolDefinition def = baseBuilder()
                    .capabilities(new String[]{})
                    .outputSchema("{bad json")
                    .build();

            assertDoesNotThrow(() -> validator.validate(def));

            // capabilities 应被填充为默认值
            assertArrayEquals(new String[]{"unknown"}, def.getCapabilities());
        }

        @Test
        @DisplayName("合法 capabilities + 合法 JSON outputSchema → 全部通过")
        void validCapabilitiesAndValidJsonShouldPass() {
            ToolDefinition def = baseBuilder()
                    .capabilities(new String[]{"read-only", "data-access"})
                    .outputSchema("{\"type\":\"string\"}")
                    .build();

            assertDoesNotThrow(() -> validator.validate(def));

            assertArrayEquals(new String[]{"read-only", "data-access"}, def.getCapabilities());
        }
    }
}
