package com.example.smartassistant.common.gateway.tool;

import com.example.smartassistant.common.error.AgentErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ToolDefinition} 单元测试。
 * <p>
 * 验证静态工厂方法的 capabilities 标签设置及其他关键字段。
 * </p>
 */
class ToolDefinitionTest {

    // ==================== read() 工厂方法 ====================

    @Nested
    @DisplayName("read() 工厂方法")
    class ReadFactoryMethod {

        @Test
        @DisplayName("read() 应设置 capabilities=[read-only]")
        void readShouldSetReadOnlyCapability() {
            ToolDefinition def = ToolDefinition.read("query-order", "查询订单");

            assertArrayEquals(new String[]{"read-only"}, def.getCapabilities());
        }

        @Test
        @DisplayName("read() 应设置 riskLevel=READ")
        void readShouldSetReadRiskLevel() {
            ToolDefinition def = ToolDefinition.read("query-order", "查询订单");

            assertEquals(ToolRiskLevel.READ, def.getRiskLevel());
            assertTrue(def.isReadOnly());
        }

        @Test
        @DisplayName("read() 应设置 needsApproval=false")
        void readShouldSetNeedsApprovalFalse() {
            ToolDefinition def = ToolDefinition.read("query-order", "查询订单");

            assertFalse(def.isNeedsApproval());
        }

        @Test
        @DisplayName("read() 应设置 status=ACTIVE")
        void readShouldSetActiveStatus() {
            ToolDefinition def = ToolDefinition.read("query-order", "查询订单");

            assertEquals(ToolStatus.ACTIVE, def.getStatus());
        }

        @Test
        @DisplayName("read() 带标签应正确设置 tags")
        void readWithTagsShouldSetTags() {
            String[] tags = {"ORDER", "READ_ONLY"};
            ToolDefinition def = ToolDefinition.read("query-order", "查询订单", tags);

            assertArrayEquals(new String[]{"read-only"}, def.getCapabilities());
            assertArrayEquals(tags, def.getTags());
        }

        @Test
        @DisplayName("read() 带 null 标签应设为空数组")
        void readWithNullTagsShouldSetEmptyArray() {
            ToolDefinition def = ToolDefinition.read("query-order", "查询订单", null);

            assertArrayEquals(new String[0], def.getTags());
        }
    }

    // ==================== write() 工厂方法 ====================

    @Nested
    @DisplayName("write() 工厂方法")
    class WriteFactoryMethod {

        @Test
        @DisplayName("write(LOW) 应设置 capabilities=[mutate-state]")
        void writeLowShouldSetMutateStateCapability() {
            ToolDefinition def = ToolDefinition.write("favorite", "收藏商品", ToolRiskLevel.LOW);

            assertArrayEquals(new String[]{"mutate-state"}, def.getCapabilities());
        }

        @Test
        @DisplayName("write(MEDIUM) 应设置 capabilities=[mutate-state]")
        void writeMediumShouldSetMutateStateCapability() {
            ToolDefinition def = ToolDefinition.write("update-config", "更新配置", ToolRiskLevel.MEDIUM);

            assertArrayEquals(new String[]{"mutate-state"}, def.getCapabilities());
        }

        @Test
        @DisplayName("write(HIGH) 应设置 capabilities=[mutate-state, payment]")
        void writeHighShouldSetMutateStateAndPaymentCapabilities() {
            ToolDefinition def = ToolDefinition.write("refund", "退款", ToolRiskLevel.HIGH);

            assertArrayEquals(new String[]{"mutate-state", "payment"}, def.getCapabilities());
        }

        @Test
        @DisplayName("write(READ) 应设置 capabilities=[read-only]")
        void writeReadShouldSetReadOnlyCapability() {
            ToolDefinition def = ToolDefinition.write("search", "搜索", ToolRiskLevel.READ);

            assertArrayEquals(new String[]{"read-only"}, def.getCapabilities());
        }

        @Test
        @DisplayName("write(HIGH) 应设置 needsApproval=true")
        void writeHighShouldSetNeedsApprovalTrue() {
            ToolDefinition def = ToolDefinition.write("refund", "退款", ToolRiskLevel.HIGH);

            assertTrue(def.isNeedsApproval());
        }

        @Test
        @DisplayName("write(LOW) 应设置 needsApproval=false")
        void writeLowShouldSetNeedsApprovalFalse() {
            ToolDefinition def = ToolDefinition.write("favorite", "收藏", ToolRiskLevel.LOW);

            assertFalse(def.isNeedsApproval());
        }
    }

    // ==================== highRisk() 工厂方法 ====================

    @Nested
    @DisplayName("highRisk() 工厂方法")
    class HighRiskFactoryMethod {

        @Test
        @DisplayName("highRisk() 应设置 capabilities=[mutate-state, payment]")
        void highRiskShouldSetMutateStateAndPaymentCapabilities() {
            ToolDefinition def = ToolDefinition.highRisk("refund-order", "退款", true);

            assertArrayEquals(new String[]{"mutate-state", "payment"}, def.getCapabilities());
        }

        @Test
        @DisplayName("highRisk() 应设置 riskLevel=HIGH")
        void highRiskShouldSetHighRiskLevel() {
            ToolDefinition def = ToolDefinition.highRisk("refund-order", "退款", true);

            assertEquals(ToolRiskLevel.HIGH, def.getRiskLevel());
            assertTrue(def.isHighRisk());
        }

        @Test
        @DisplayName("highRisk() 应设置 timeout=15s")
        void highRiskShouldSet15sTimeout() {
            ToolDefinition def = ToolDefinition.highRisk("refund-order", "退款", true);

            assertEquals(Duration.ofSeconds(15), def.getTimeout());
        }

        @Test
        @DisplayName("highRisk() 应设置 maxRetries=1")
        void highRiskShouldSetMaxRetries1() {
            ToolDefinition def = ToolDefinition.highRisk("refund-order", "退款", true);

            assertEquals(1, def.getMaxRetries());
        }

        @Test
        @DisplayName("highRisk() 应设置 rateLimit=10")
        void highRiskShouldSetRateLimit10() {
            ToolDefinition def = ToolDefinition.highRisk("refund-order", "退款", true);

            assertEquals(10, def.getRateLimit());
        }

        @Test
        @DisplayName("highRisk(true) 应设置 needsApproval=true")
        void highRiskWithApprovalShouldSetNeedsApprovalTrue() {
            ToolDefinition def = ToolDefinition.highRisk("refund-order", "退款", true);

            assertTrue(def.isNeedsApproval());
        }

        @Test
        @DisplayName("highRisk(false) 应设置 needsApproval=false")
        void highRiskWithoutApprovalShouldSetNeedsApprovalFalse() {
            ToolDefinition def = ToolDefinition.highRisk("refund-order", "退款", false);

            assertFalse(def.isNeedsApproval());
        }
    }

    // ==================== 默认 capabilities ====================

    @Nested
    @DisplayName("默认 capabilities")
    class DefaultCapabilities {

        @Test
        @DisplayName("Builder 不指定 capabilities 时默认为 [unknown]")
        void builderDefaultCapabilitiesShouldBeUnknown() {
            ToolDefinition def = ToolDefinition.builder()
                    .name("test-tool")
                    .description("测试")
                    .build();

            assertArrayEquals(new String[]{"unknown"}, def.getCapabilities());
        }

        @Test
        @DisplayName("Builder 指定 capabilities 时使用指定值")
        void builderCustomCapabilitiesShouldBeUsed() {
            ToolDefinition def = ToolDefinition.builder()
                    .name("test-tool")
                    .description("测试")
                    .capabilities(new String[]{"read-only", "data-access"})
                    .build();

            assertArrayEquals(new String[]{"read-only", "data-access"}, def.getCapabilities());
        }
    }

    // ==================== 其他属性 ====================

    @Nested
    @DisplayName("其他属性验证")
    class OtherProperties {

        @Test
        @DisplayName("默认 status 应为 ACTIVE")
        void defaultStatusShouldBeActive() {
            ToolDefinition def = ToolDefinition.builder()
                    .name("test")
                    .description("test")
                    .build();

            assertEquals(ToolStatus.ACTIVE, def.getStatus());
        }

        @Test
        @DisplayName("默认 version 应为 1.0.0")
        void defaultVersionShouldBe100() {
            ToolDefinition def = ToolDefinition.builder()
                    .name("test")
                    .description("test")
                    .build();

            assertEquals("1.0.0", def.getVersion());
        }

        @Test
        @DisplayName("默认 timeout 应为 10s")
        void defaultTimeoutShouldBe10s() {
            ToolDefinition def = ToolDefinition.builder()
                    .name("test")
                    .description("test")
                    .build();

            assertEquals(Duration.ofSeconds(10), def.getTimeout());
        }

        @Test
        @DisplayName("incrementAndGetUseCount 应原子递增调用计数")
        void incrementAndGetUseCountShouldIncrement() {
            ToolDefinition def = ToolDefinition.builder()
                    .name("test")
                    .description("test")
                    .build();

            assertEquals(0, def.getUseCount());
            assertEquals(1, def.incrementAndGetUseCount());
            assertEquals(2, def.incrementAndGetUseCount());
            assertEquals(2, def.getUseCount());
        }

        @Test
        @DisplayName("getErrorCode — READ 返回 DATA_NOT_FOUND")
        void errorCodeForRead() {
            ToolDefinition def = ToolDefinition.read("test", "test");

            assertEquals(AgentErrorCode.DATA_NOT_FOUND, def.getErrorCode());
        }

        @Test
        @DisplayName("getErrorCode — HIGH 返回 PERMISSION_DENIED")
        void errorCodeForHigh() {
            ToolDefinition def = ToolDefinition.highRisk("test", "test", true);

            assertEquals(AgentErrorCode.PERMISSION_DENIED, def.getErrorCode());
        }

        @Test
        @DisplayName("getErrorCode — LOW/MEDIUM 返回 TOOL_EXECUTION_FAILED")
        void errorCodeForLowAndMedium() {
            ToolDefinition low = ToolDefinition.write("test", "test", ToolRiskLevel.LOW);
            ToolDefinition medium = ToolDefinition.write("test", "test", ToolRiskLevel.MEDIUM);

            assertEquals(AgentErrorCode.TOOL_EXECUTION_FAILED, low.getErrorCode());
            assertEquals(AgentErrorCode.TOOL_EXECUTION_FAILED, medium.getErrorCode());
        }

        @Test
        @DisplayName("equals/hashCode 仅基于 name 字段")
        void equalsShouldBeBasedOnNameOnly() {
            ToolDefinition def1 = ToolDefinition.builder().name("same-name").description("desc1").build();
            ToolDefinition def2 = ToolDefinition.builder().name("same-name").description("desc2").build();
            ToolDefinition def3 = ToolDefinition.builder().name("different-name").description("desc1").build();

            assertEquals(def1, def2);
            assertEquals(def1.hashCode(), def2.hashCode());
            assertNotEquals(def1, def3);
        }
    }
}
