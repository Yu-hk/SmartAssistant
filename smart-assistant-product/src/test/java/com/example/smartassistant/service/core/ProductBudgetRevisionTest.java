package com.example.smartassistant.service.core;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ProductBudgetRevisionTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "之前预算2000元，现在预算改为1000元", "不要按预算2000元推荐，这次预算1000元",
        "这次预算1000元，之前预算2000元", "预算不是2000元，这次预算1000元",
        "之前预算2000元现在预算1000元", "预算2000元，预算调整为1000元",
        "不要按2000元以内推荐，本次1000元以内", "预算只有1000元，重量不超过1.3kg",
        "预算1千元以内", "预算1000元，不超过1000元"
    })
    void currentBudgetWins(String question) {
        var result = ProductDiscoveryService.resolveBudget(question);
        assertFalse(result.ambiguous());
        assertNotNull(result.max());
        assertEquals(0, new BigDecimal("1000").compareTo(result.max()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"预算1000元，预算2000元", "预算1000-2000元", "预算1000元到2000元"})
    void conflictingOrRangeBudgetRequiresConfirmation(String question) {
        var result = ProductDiscoveryService.resolveBudget(question);
        assertTrue(result.ambiguous());
        assertNull(result.max());
        var recommendation = new StructuredProductRecommendation(question, java.util.List.of(
            java.util.Map.of("code", "fixture", "name", "测试商品", "price", 1500, "stock", "有货")));
        assertFalse(recommendation.hasEligibleProducts());
        assertTrue(recommendation.noEligibleAnswer().contains("确认"));
        var discovery = new ProductDiscoveryService(new com.example.smartassistant.spi.InMemoryProductBackend());
        assertTrue(discovery.discover(question, "耳机", 5).clarificationRequired());
    }
}
