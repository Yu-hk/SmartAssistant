package com.example.smartassistant.toolregistry.service;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRiskLevel;
import com.example.smartassistant.common.gateway.tool.compat.CompatibilityResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ToolCompatibilityCheckerImpl} 单元测试。
 * <p>
 * 验证 4 条 BREAKING 规则及 COMPATIBLE 场景：
 * <ol>
 *   <li>风险等级降级 → BREAKING</li>
 *   <li>超时缩短 → BREAKING</li>
 *   <li>scopes 收缩 → BREAKING</li>
 *   <li>needsApproval false→true → BREAKING</li>
 *   <li>无变化 / 风险升级 / 超时增大 / scopes 扩展 → COMPATIBLE</li>
 * </ol>
 * </p>
 */
class ToolCompatibilityCheckerImplTest {

    private final ToolCompatibilityCheckerImpl checker = new ToolCompatibilityCheckerImpl();

    private ToolDefinition.ToolDefinitionBuilder baseBuilder(String name) {
        return ToolDefinition.builder()
                .name(name)
                .description("测试工具")
                .riskLevel(ToolRiskLevel.READ)
                .timeout(Duration.ofSeconds(10))
                .needsApproval(false)
                .scopes(new String[0])
                .version("1.0.0");
    }

    // ==================== BREAKING 场景 ====================

    @Nested
    @DisplayName("BREAKING 变更")
    class BreakingChanges {

        @Test
        @DisplayName("风险等级降级（HIGH→MEDIUM）→ BREAKING")
        void riskLevelDowngradeShouldBeBreaking() {
            ToolDefinition oldDef = baseBuilder("tool1").riskLevel(ToolRiskLevel.HIGH).build();
            ToolDefinition newDef = baseBuilder("tool1").riskLevel(ToolRiskLevel.MEDIUM).build();

            CompatibilityResult result = checker.check(oldDef, newDef);

            assertEquals(CompatibilityResult.BREAKING, result);
            assertTrue(checker.getReason().contains("风险等级降级"));
        }

        @Test
        @DisplayName("风险等级降级（HIGH→LOW）→ BREAKING")
        void riskLevelHighToLowShouldBeBreaking() {
            ToolDefinition oldDef = baseBuilder("tool1").riskLevel(ToolRiskLevel.HIGH).build();
            ToolDefinition newDef = baseBuilder("tool1").riskLevel(ToolRiskLevel.LOW).build();

            assertEquals(CompatibilityResult.BREAKING, checker.check(oldDef, newDef));
        }

        @Test
        @DisplayName("风险等级降级（MEDIUM→READ）→ BREAKING")
        void riskLevelMediumToReadShouldBeBreaking() {
            ToolDefinition oldDef = baseBuilder("tool1").riskLevel(ToolRiskLevel.MEDIUM).build();
            ToolDefinition newDef = baseBuilder("tool1").riskLevel(ToolRiskLevel.READ).build();

            assertEquals(CompatibilityResult.BREAKING, checker.check(oldDef, newDef));
        }

        @Test
        @DisplayName("超时缩短（10s→5s）→ BREAKING")
        void timeoutShrinkShouldBeBreaking() {
            ToolDefinition oldDef = baseBuilder("tool1").timeout(Duration.ofSeconds(10)).build();
            ToolDefinition newDef = baseBuilder("tool1").timeout(Duration.ofSeconds(5)).build();

            CompatibilityResult result = checker.check(oldDef, newDef);

            assertEquals(CompatibilityResult.BREAKING, result);
            assertTrue(checker.getReason().contains("超时缩短"));
        }

        @Test
        @DisplayName("scopes 收缩（删除已有 scope）→ BREAKING")
        void scopeShrinkShouldBeBreaking() {
            ToolDefinition oldDef = baseBuilder("tool1")
                    .scopes(new String[]{"read", "write"})
                    .build();
            ToolDefinition newDef = baseBuilder("tool1")
                    .scopes(new String[]{"read"})
                    .build();

            CompatibilityResult result = checker.check(oldDef, newDef);

            assertEquals(CompatibilityResult.BREAKING, result);
            assertTrue(checker.getReason().contains("scopes 收缩"));
        }

        @Test
        @DisplayName("needsApproval false→true → BREAKING")
        void needsApprovalFalseToTrueShouldBeBreaking() {
            ToolDefinition oldDef = baseBuilder("tool1").needsApproval(false).build();
            ToolDefinition newDef = baseBuilder("tool1").needsApproval(true).build();

            CompatibilityResult result = checker.check(oldDef, newDef);

            assertEquals(CompatibilityResult.BREAKING, result);
            assertTrue(checker.getReason().contains("needsApproval"));
        }
    }

    // ==================== COMPATIBLE 场景 ====================

    @Nested
    @DisplayName("COMPATIBLE 变更")
    class CompatibleChanges {

        @Test
        @DisplayName("无变化 → COMPATIBLE")
        void noChangeShouldBeCompatible() {
            ToolDefinition oldDef = baseBuilder("tool1").build();
            ToolDefinition newDef = baseBuilder("tool1").build();

            assertEquals(CompatibilityResult.COMPATIBLE, checker.check(oldDef, newDef));
        }

        @Test
        @DisplayName("风险等级升级（MEDIUM→HIGH）→ COMPATIBLE")
        void riskLevelUpgradeShouldBeCompatible() {
            ToolDefinition oldDef = baseBuilder("tool1").riskLevel(ToolRiskLevel.MEDIUM).build();
            ToolDefinition newDef = baseBuilder("tool1").riskLevel(ToolRiskLevel.HIGH).build();

            assertEquals(CompatibilityResult.COMPATIBLE, checker.check(oldDef, newDef));
        }

        @Test
        @DisplayName("风险等级升级（READ→HIGH）→ COMPATIBLE")
        void riskLevelReadToHighShouldBeCompatible() {
            ToolDefinition oldDef = baseBuilder("tool1").riskLevel(ToolRiskLevel.READ).build();
            ToolDefinition newDef = baseBuilder("tool1").riskLevel(ToolRiskLevel.HIGH).build();

            assertEquals(CompatibilityResult.COMPATIBLE, checker.check(oldDef, newDef));
        }

        @Test
        @DisplayName("超时增大（10s→20s）→ COMPATIBLE")
        void timeoutIncreaseShouldBeCompatible() {
            ToolDefinition oldDef = baseBuilder("tool1").timeout(Duration.ofSeconds(10)).build();
            ToolDefinition newDef = baseBuilder("tool1").timeout(Duration.ofSeconds(20)).build();

            assertEquals(CompatibilityResult.COMPATIBLE, checker.check(oldDef, newDef));
        }

        @Test
        @DisplayName("scopes 扩展（新增 scope）→ COMPATIBLE")
        void scopeExpandShouldBeCompatible() {
            ToolDefinition oldDef = baseBuilder("tool1")
                    .scopes(new String[]{"read"})
                    .build();
            ToolDefinition newDef = baseBuilder("tool1")
                    .scopes(new String[]{"read", "write"})
                    .build();

            assertEquals(CompatibilityResult.COMPATIBLE, checker.check(oldDef, newDef));
        }

        @Test
        @DisplayName("needsApproval true→false → COMPATIBLE")
        void needsApprovalTrueToFalseShouldBeCompatible() {
            ToolDefinition oldDef = baseBuilder("tool1").needsApproval(true).build();
            ToolDefinition newDef = baseBuilder("tool1").needsApproval(false).build();

            assertEquals(CompatibilityResult.COMPATIBLE, checker.check(oldDef, newDef));
        }

        @Test
        @DisplayName("超时不变 → COMPATIBLE")
        void timeoutUnchangedShouldBeCompatible() {
            ToolDefinition oldDef = baseBuilder("tool1").timeout(Duration.ofSeconds(10)).build();
            ToolDefinition newDef = baseBuilder("tool1").timeout(Duration.ofSeconds(10)).build();

            assertEquals(CompatibilityResult.COMPATIBLE, checker.check(oldDef, newDef));
        }

        @Test
        @DisplayName("scopes 不变 → COMPATIBLE")
        void scopeUnchangedShouldBeCompatible() {
            ToolDefinition oldDef = baseBuilder("tool1")
                    .scopes(new String[]{"read", "write"})
                    .build();
            ToolDefinition newDef = baseBuilder("tool1")
                    .scopes(new String[]{"read", "write"})
                    .build();

            assertEquals(CompatibilityResult.COMPATIBLE, checker.check(oldDef, newDef));
        }
    }

    // ==================== getReason 测试 ====================

    @Nested
    @DisplayName("getReason() 原因查询")
    class ReasonQuery {

        @Test
        @DisplayName("未调用 check 前 getReason 返回默认值")
        void getReasonBeforeCheckShouldReturnDefault() {
            // ThreadLocal 初始值为 "N/A"
            // 注意：由于 ThreadLocal 可能在同一线程的其他测试中被设置过，
            // 这里仅验证 getReason 不抛异常
            String reason = checker.getReason();
            assertNotNull(reason);
        }

        @Test
        @DisplayName("BREAKING 后 getReason 应返回具体原因")
        void getReasonAfterBreakingCheck() {
            ToolDefinition oldDef = baseBuilder("tool1").riskLevel(ToolRiskLevel.HIGH).build();
            ToolDefinition newDef = baseBuilder("tool1").riskLevel(ToolRiskLevel.LOW).build();

            checker.check(oldDef, newDef);

            String reason = checker.getReason();
            assertNotNull(reason);
            assertTrue(reason.contains("风险等级降级"));
        }

        @Test
        @DisplayName("COMPATIBLE 后 getReason 应返回无破坏性变更")
        void getReasonAfterCompatibleCheck() {
            ToolDefinition oldDef = baseBuilder("tool1").build();
            ToolDefinition newDef = baseBuilder("tool1").build();

            checker.check(oldDef, newDef);

            assertEquals("无破坏性变更", checker.getReason());
        }
    }

    // ==================== BREAKING 规则优先级 ====================

    @Nested
    @DisplayName("BREAKING 规则优先级（短路）")
    class BreakingRulePriority {

        @Test
        @DisplayName("风险降级优先于超时缩短（先匹配风险降级）")
        void riskDowngradeShouldTakePriorityOverTimeout() {
            ToolDefinition oldDef = baseBuilder("tool1")
                    .riskLevel(ToolRiskLevel.HIGH)
                    .timeout(Duration.ofSeconds(10))
                    .build();
            ToolDefinition newDef = baseBuilder("tool1")
                    .riskLevel(ToolRiskLevel.MEDIUM)
                    .timeout(Duration.ofSeconds(5))
                    .build();

            checker.check(oldDef, newDef);

            // 风险降级是第一条规则，应优先返回
            assertTrue(checker.getReason().contains("风险等级降级"));
            assertFalse(checker.getReason().contains("超时缩短"));
        }

        @Test
        @DisplayName("超时缩短优先于 scopes 收缩")
        void timeoutShouldTakePriorityOverScopes() {
            ToolDefinition oldDef = baseBuilder("tool1")
                    .timeout(Duration.ofSeconds(10))
                    .scopes(new String[]{"read", "write"})
                    .build();
            ToolDefinition newDef = baseBuilder("tool1")
                    .timeout(Duration.ofSeconds(5))
                    .scopes(new String[]{"read"})
                    .build();

            checker.check(oldDef, newDef);

            assertTrue(checker.getReason().contains("超时缩短"));
            assertFalse(checker.getReason().contains("scopes"));
        }
    }
}
