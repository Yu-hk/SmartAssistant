package com.example.smartassistant.common.gateway.tool;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ToolDefinition#getFunctionalCapabilities()} 字段单元测试（T1 新增）。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>read / write / highRisk 工厂的 functionalCapabilities 重载（含带 tags 变体）；</li>
 *   <li>Builder 的 {@code functionalCapabilities(List)} 与 {@code functionalCapabilities(String...)} 双重载；</li>
 *   <li>归一化语义（去重、忽略 null、保序、绝不为 null）；</li>
 *   <li>向后兼容（既有不含 functionalCapabilities 的工厂保持空列表，不影响其他字段）；</li>
 *   <li>Jackson 序列化往返后值保持一致（可选，环境允许时执行）。</li>
 * </ul>
 * </p>
 */
class ToolDefinitionFunctionalCapabilityTest {

    private static final String NAME = "test-tool";
    private static final String DESC = "测试工具描述";

    // ==================== B.1 工厂重载 ====================

    @Nested
    @DisplayName("工厂方法 functionalCapabilities 重载")
    class FactoryOverloads {

        @Test
        @DisplayName("read(name, desc, tags, List) 应携带 functionalCapabilities")
        void readWithTagsAndCapabilities() {
            ToolDefinition def = ToolDefinition.read(NAME, DESC, new String[]{"ORDER"}, List.of("product-query"));

            List<String> caps = def.getFunctionalCapabilities();
            assertNotNull(caps);
            assertEquals(List.of("product-query"), caps);
        }

        @Test
        @DisplayName("write(name, desc, riskLevel, List) 应携带 functionalCapabilities")
        void writeWithCapabilities() {
            ToolDefinition def = ToolDefinition.write(NAME, DESC, ToolRiskLevel.LOW, List.of("order-refund"));

            List<String> caps = def.getFunctionalCapabilities();
            assertNotNull(caps);
            assertEquals(List.of("order-refund"), caps);
        }

        @Test
        @DisplayName("write(name, desc, riskLevel, tags, List) 带标签重载应正确")
        void writeWithTagsAndCapabilities() {
            ToolDefinition def = ToolDefinition.write(NAME, DESC, ToolRiskLevel.MEDIUM,
                    new String[]{"ORDER"}, List.of("order-refund"));

            List<String> caps = def.getFunctionalCapabilities();
            assertNotNull(caps);
            assertEquals(List.of("order-refund"), caps);
            assertArrayEquals(new String[]{"ORDER"}, def.getTags());
        }

        @Test
        @DisplayName("highRisk(name, desc, needsApproval, List) 应携带 functionalCapabilities")
        void highRiskWithCapabilities() {
            ToolDefinition def = ToolDefinition.highRisk(NAME, DESC, true, List.of("order-pay"));

            List<String> caps = def.getFunctionalCapabilities();
            assertNotNull(caps);
            assertEquals(List.of("order-pay"), caps);
            assertTrue(def.isHighRisk());
        }

        @Test
        @DisplayName("highRisk(name, desc, needsApproval, tags, List) 带标签重载应正确")
        void highRiskWithTagsAndCapabilities() {
            ToolDefinition def = ToolDefinition.highRisk(NAME, DESC, true,
                    new String[]{"ORDER"}, List.of("order-pay"));

            List<String> caps = def.getFunctionalCapabilities();
            assertNotNull(caps);
            assertEquals(List.of("order-pay"), caps);
            assertArrayEquals(new String[]{"ORDER"}, def.getTags());
        }
    }

    // ==================== B.2 Builder 双重载 ====================

    @Nested
    @DisplayName("Builder functionalCapabilities 双重载")
    class BuilderOverloads {

        @Test
        @DisplayName("functionalCapabilities(List) 应正确设置字段")
        void builderWithList() {
            ToolDefinition def = ToolDefinition.builder()
                    .name(NAME)
                    .description(DESC)
                    .functionalCapabilities(List.of("a", "b"))
                    .build();

            assertEquals(List.of("a", "b"), def.getFunctionalCapabilities());
        }

        @Test
        @DisplayName("functionalCapabilities(String...) 可变参数应正确设置字段")
        void builderWithVarargs() {
            ToolDefinition def = ToolDefinition.builder()
                    .name(NAME)
                    .description(DESC)
                    .functionalCapabilities("a", "b")
                    .build();

            assertEquals(List.of("a", "b"), def.getFunctionalCapabilities());
        }
    }

    // ==================== B.3 归一化语义 ====================

    @Nested
    @DisplayName("functionalCapabilities 归一化语义")
    class Normalization {

        @Test
        @DisplayName("去重：传入 [a,a,b] 结果应为 [a,b]")
        void shouldDeduplicate() {
            ToolDefinition def = ToolDefinition.builder()
                    .name(NAME)
                    .description(DESC)
                    .functionalCapabilities(Arrays.asList("a", "a", "b"))
                    .build();

            assertEquals(List.of("a", "b"), def.getFunctionalCapabilities());
        }

        @Test
        @DisplayName("忽略 null 元素：传入 [a,null,b] 结果应为 [a,b]（不 NPE）")
        void shouldIgnoreNullElements() {
            ToolDefinition def = ToolDefinition.builder()
                    .name(NAME)
                    .description(DESC)
                    .functionalCapabilities(Arrays.asList("a", null, "b"))
                    .build();

            assertEquals(List.of("a", "b"), def.getFunctionalCapabilities());
        }

        @Test
        @DisplayName("保序：传入 [x,y,z] 结果应保持 [x,y,z]")
        void shouldPreserveOrder() {
            ToolDefinition def = ToolDefinition.builder()
                    .name(NAME)
                    .description(DESC)
                    .functionalCapabilities(Arrays.asList("x", "y", "z"))
                    .build();

            assertEquals(List.of("x", "y", "z"), def.getFunctionalCapabilities());
        }

        @Test
        @DisplayName("绝不为 null：Builder 未设置时返回非 null 空列表")
        void builderDefaultShouldBeNonNullEmptyList() {
            ToolDefinition def = ToolDefinition.builder()
                    .name(NAME)
                    .description(DESC)
                    .build();

            List<String> caps = def.getFunctionalCapabilities();
            assertNotNull(caps);
            assertTrue(caps.isEmpty());
        }

        @Test
        @DisplayName("绝不为 null：不传 functionalCapabilities 的既有工厂返回非 null 空列表")
        void legacyFactoriesShouldReturnNonNullEmptyList() {
            assertNotNullAndEmpty(ToolDefinition.read(NAME, DESC));
            assertNotNullAndEmpty(ToolDefinition.read(NAME, DESC, new String[]{"ORDER"}));
            assertNotNullAndEmpty(ToolDefinition.write(NAME, DESC, ToolRiskLevel.LOW));
            assertNotNullAndEmpty(ToolDefinition.write(NAME, DESC, ToolRiskLevel.LOW, new String[]{"ORDER"}));
            assertNotNullAndEmpty(ToolDefinition.highRisk(NAME, DESC, true));
            assertNotNullAndEmpty(ToolDefinition.highRisk(NAME, DESC, true, new String[]{"ORDER"}));
        }

        @Test
        @DisplayName("setter 传入 null 仍应为非 null 空列表（不 NPE）")
        void setterWithNullShouldBeNonNullEmptyList() {
            ToolDefinition def = ToolDefinition.builder().name(NAME).description(DESC).build();

            assertDoesNotThrow(() -> def.setFunctionalCapabilities(null));

            List<String> caps = def.getFunctionalCapabilities();
            assertNotNull(caps);
            assertTrue(caps.isEmpty());
        }

        @Test
        @DisplayName("setter 传入含重复/null 的列表应同样去重 + 忽略 null")
        void setterWithDuplicatesAndNullShouldNormalize() {
            ToolDefinition def = ToolDefinition.builder().name(NAME).description(DESC).build();

            def.setFunctionalCapabilities(Arrays.asList("a", null, "a", "b"));

            assertEquals(List.of("a", "b"), def.getFunctionalCapabilities());
        }

        private void assertNotNullAndEmpty(ToolDefinition def) {
            List<String> caps = def.getFunctionalCapabilities();
            assertNotNull(caps, "functionalCapabilities 不应为 null：name=" + def.getName());
            assertTrue(caps.isEmpty(), "functionalCapabilities 应为空列表：name=" + def.getName());
        }
    }

    // ==================== B.4 向后兼容 ====================

    @Nested
    @DisplayName("向后兼容（既有工厂不受影响）")
    class BackwardCompatibility {

        @Test
        @DisplayName("read(name, desc, tags) 3 参工厂：functionalCapabilities 为空且其他字段正确")
        void legacyReadThreeArgKeepsOtherFields() {
            String[] tags = {"ORDER", "READ_ONLY"};
            ToolDefinition def = ToolDefinition.read(NAME, DESC, tags);

            // functionalCapabilities 保持空列表
            List<String> caps = def.getFunctionalCapabilities();
            assertNotNull(caps);
            assertTrue(caps.isEmpty());

            // 其他字段不受影响
            assertEquals(ToolRiskLevel.READ, def.getRiskLevel());
            assertTrue(def.isReadOnly());
            assertArrayEquals(new String[]{"read-only"}, def.getCapabilities());
            assertArrayEquals(tags, def.getTags());
            assertEquals(ToolStatus.ACTIVE, def.getStatus());
        }
    }

    // ==================== B.5 序列化往返（可选，环境允许时） ====================

    @Nested
    @DisplayName("Jackson 序列化往返")
    class Serialization {

        @Test
        @DisplayName("带 functionalCapabilities 的对象经 Jackson 往返后值保持一致")
        void jacksonRoundTripPreservesFunctionalCapabilities() throws Exception {
            // common 模块未引入 jackson-datatype-jsr310，Duration 默认不被支持，
            // 故在测试侧注册轻量 SimpleModule 处理 Duration 的序列化/反序列化（不修改任何源码）。
            SimpleModule durationModule = new SimpleModule();
            durationModule.addSerializer(Duration.class, new JsonSerializer<Duration>() {
                @Override
                public void serialize(Duration value, JsonGenerator gen,
                                       com.fasterxml.jackson.databind.SerializerProvider serializers)
                        throws IOException {
                    gen.writeString(value.toString());
                }
            });
            durationModule.addDeserializer(Duration.class, new JsonDeserializer<Duration>() {
                @Override
                public Duration deserialize(JsonParser p, DeserializationContext ctxt)
                        throws IOException {
                    return Duration.parse(p.getValueAsString());
                }
            });

            ObjectMapper mapper = new ObjectMapper()
                    .registerModule(durationModule)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            ToolDefinition def = ToolDefinition.builder()
                    .name("round-trip-tool")
                    .description("测试往返")
                    .functionalCapabilities(List.of("order-query", "order-refund"))
                    .build();

            String json = mapper.writeValueAsString(def);
            ToolDefinition back = mapper.readValue(json, ToolDefinition.class);

            assertEquals(def.getFunctionalCapabilities(), back.getFunctionalCapabilities());
        }
    }
}
