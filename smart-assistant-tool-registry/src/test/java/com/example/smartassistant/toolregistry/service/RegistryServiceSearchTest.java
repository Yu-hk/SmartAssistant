package com.example.smartassistant.toolregistry.service;

import com.example.smartassistant.common.gateway.tool.ToolDefinition;
import com.example.smartassistant.common.gateway.tool.ToolRiskLevel;
import com.example.smartassistant.common.gateway.tool.ToolStatus;
import com.example.smartassistant.common.gateway.tool.ToolTier;
import com.example.smartassistant.common.gateway.tool.compat.ToolCompatibilityChecker;
import com.example.smartassistant.toolregistry.service.ToolManifestValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link RegistryService#search(String[], String, String, String, String, int)} 单元测试（T2a）。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>functionalCapabilities OR 语义（任一命中即匹配）；</li>
 *   <li>functionalCapabilities AND 语义（全部命中才匹配）；</li>
 *   <li>keyword 大小写不敏感包含匹配（name / description）；</li>
 *   <li>tier 分层精确匹配；</li>
 *   <li>status 状态精确匹配（null/blank 表示不限制）；</li>
 *   <li>limit 截断（limit&gt;0 时截断返回数量）。</li>
 * </ul>
 */
class RegistryServiceSearchTest {

    private RegistryService registryService;

    @BeforeEach
    void setUp() {
        ToolCompatibilityChecker compatibilityChecker = mock(ToolCompatibilityChecker.class);
        ToolManifestValidator manifestValidator = mock(ToolManifestValidator.class);
        // validate 默认 no-op；新注册不触发兼容性检查
        doNothing().when(manifestValidator).validate(any(ToolDefinition.class));

        registryService = new RegistryService(compatibilityChecker, manifestValidator);
        seedCatalog();
    }

    private void seedCatalog() {
        registryService.register(ToolDefinition.builder()
                .name("queryOrder")
                .description("查询订单详情")
                .riskLevel(ToolRiskLevel.READ)
                .toolTier(ToolTier.CORE)
                .status(ToolStatus.ACTIVE)
                .functionalCapabilities(List.of("order-query"))
                .build());

        registryService.register(ToolDefinition.builder()
                .name("refundOrder")
                .description("退款订单")
                .riskLevel(ToolRiskLevel.HIGH)
                .toolTier(ToolTier.CORE)
                .status(ToolStatus.ACTIVE)
                .functionalCapabilities(List.of("order-refund"))
                .build());

        registryService.register(ToolDefinition.builder()
                .name("queryProduct")
                .description("查询商品信息")
                .riskLevel(ToolRiskLevel.READ)
                .toolTier(ToolTier.SHARED)
                .status(ToolStatus.ACTIVE)
                .functionalCapabilities(List.of("product-query", "search"))
                .build());

        registryService.register(ToolDefinition.builder()
                .name("sqlReport")
                .description("SQL 报表生成")
                .riskLevel(ToolRiskLevel.READ)
                .toolTier(ToolTier.SHARED)
                .status(ToolStatus.ACTIVE)
                .functionalCapabilities(List.of("sql-query", "report"))
                .build());

        registryService.register(ToolDefinition.builder()
                .name("legacyTool")
                .description("旧的遗留工具")
                .riskLevel(ToolRiskLevel.READ)
                .toolTier(ToolTier.EXTENSION)
                .status(ToolStatus.DEPRECATED)
                .functionalCapabilities(List.of("legacy"))
                .build());
    }

    private List<String> names(List<ToolDefinition> defs) {
        return defs.stream().map(ToolDefinition::getName).toList();
    }

    // ==================== functionalCapabilities OR ====================

    @Test
    @DisplayName("functionalCapabilities OR：命中任一即可（order-query → queryOrder）")
    void orMatchSingleCapability() {
        List<ToolDefinition> result = registryService.search(
                new String[]{"order-query"}, null, "OR", null, null, 0);
        assertEquals(List.of("queryOrder"), names(result));
    }

    @Test
    @DisplayName("functionalCapabilities OR：多个令牌并集（order-query / product-query → 2 个）")
    void orMatchMultipleCapabilities() {
        List<ToolDefinition> result = registryService.search(
                new String[]{"order-query", "product-query"}, null, "OR", null, null, 0);
        assertEquals(2, result.size());
        assertTrue(names(result).containsAll(List.of("queryOrder", "queryProduct")));
    }

    @Test
    @DisplayName("functionalCapabilities 默认 OR（不传 matchMode）")
    void defaultOrWhenMatchModeBlank() {
        List<ToolDefinition> result = registryService.search(
                new String[]{"order-query", "product-query"}, null, null, null, null, 0);
        assertEquals(2, result.size());
    }

    // ==================== functionalCapabilities AND ====================

    @Test
    @DisplayName("functionalCapabilities AND：必须全部命中（sql-query + report → sqlReport）")
    void andMatchAllCapabilities() {
        List<ToolDefinition> result = registryService.search(
                new String[]{"sql-query", "report"}, null, "AND", null, null, 0);
        assertEquals(List.of("sqlReport"), names(result));
    }

    @Test
    @DisplayName("functionalCapabilities AND：缺一则不匹配（sql-query + order-refund → 0）")
    void andMatchPartialFails() {
        List<ToolDefinition> result = registryService.search(
                new String[]{"sql-query", "order-refund"}, null, "AND", null, null, 0);
        assertTrue(result.isEmpty());
    }

    // ==================== keyword ====================

    @Test
    @DisplayName("keyword：在 description 上大小写不敏感包含匹配（订单 → queryOrder + refundOrder）")
    void keywordDescriptionMatchCaseInsensitive() {
        List<ToolDefinition> result = registryService.search(
                null, "订单", "OR", null, null, 0);
        assertEquals(2, result.size());
        assertTrue(names(result).containsAll(List.of("queryOrder", "refundOrder")));
    }

    @Test
    @DisplayName("keyword：英文字母大小写不敏感（SQL → sqlReport）")
    void keywordEnglishCaseInsensitive() {
        List<ToolDefinition> result = registryService.search(
                null, "SQL", "OR", null, null, 0);
        assertEquals(List.of("sqlReport"), names(result));
    }

    @Test
    @DisplayName("keyword：在 name 上匹配（Product → queryProduct）")
    void keywordNameMatch() {
        List<ToolDefinition> result = registryService.search(
                null, "Product", "OR", null, null, 0);
        assertEquals(List.of("queryProduct"), names(result));
    }

    // ==================== tier ====================

    @Test
    @DisplayName("tier：按分层精确匹配（SHARED → queryProduct + sqlReport）")
    void tierExactMatch() {
        List<ToolDefinition> result = registryService.search(
                null, null, "OR", "SHARED", null, 0);
        assertEquals(2, result.size());
        assertTrue(names(result).containsAll(List.of("queryProduct", "sqlReport")));
    }

    // ==================== status ====================

    @Test
    @DisplayName("status：按状态精确匹配（DEPRECATED → legacyTool）")
    void statusExactMatch() {
        List<ToolDefinition> result = registryService.search(
                null, null, "OR", null, "DEPRECATED", 0);
        assertEquals(List.of("legacyTool"), names(result));
    }

    @Test
    @DisplayName("status：非法值被忽略且不报错（返回全部 ACTIVE）")
    void invalidStatusIsIgnored() {
        List<ToolDefinition> result = registryService.search(
                null, null, "OR", null, "NOT_A_STATUS", 0);
        // 非法 status 被忽略 → 不过滤状态 → 全部 5 个
        assertEquals(5, result.size());
    }

    // ==================== 组合维度 ====================

    @Test
    @DisplayName("组合：functionalCapabilities + tier 取交集（sql-query & SHARED → sqlReport）")
    void combinedCapabilityAndTier() {
        List<ToolDefinition> result = registryService.search(
                new String[]{"sql-query"}, null, "OR", "SHARED", null, 0);
        assertEquals(List.of("sqlReport"), names(result));
    }

    // ==================== limit ====================

    @Test
    @DisplayName("limit：limit>0 截断返回数量")
    void limitTruncation() {
        // OR 全部 functionalCapabilities → 命中 5 个（含 DEPRECATED）
        List<ToolDefinition> result = registryService.search(
                new String[]{"order-query", "order-refund", "product-query", "search",
                        "sql-query", "report", "legacy"},
                null, "OR", null, null, 2);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("limit：limit=0 不截断")
    void limitZeroNoTruncation() {
        List<ToolDefinition> result = registryService.search(
                new String[]{"order-query", "order-refund", "product-query", "search",
                        "sql-query", "report", "legacy"},
                null, "OR", null, null, 0);
        assertEquals(5, result.size());
    }
}
