package com.example.smartassistant.service.quality;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductToolEvidenceScopeTest {
    @Test void boundedSuccessfulObservationsOnlyAndNoCrossRequestLeak() {
        ProductToolEvidenceScope.record("outside scope");
        try (var scope = ProductToolEvidenceScope.open()) {
            ProductToolEvidenceScope.record("{\"error_code\":\"PRODUCT_NOT_FOUND\"}");
            ProductToolEvidenceScope.record("商品服务暂时不可用");
            ProductToolEvidenceScope.record("x".repeat(12_001));
            assertFalse(scope.hasEvidence());
            ProductToolEvidenceScope.record("AirPods Pro 售价 1999 元，库存充足");
            ProductToolEvidenceScope.record("AirPods Pro 售价 1999 元，库存充足");
            assertEquals(1, scope.combine("").split("1999", -1).length - 1);
            assertTrue(scope.combine("文档").startsWith("文档"));
        }
        try (var next = ProductToolEvidenceScope.open()) { assertFalse(next.hasEvidence()); }
    }

    @Test void parallelThreadCannotPolluteAnotherRequest() throws Exception {
        try (var scope = ProductToolEvidenceScope.open()) {
            var task = new java.util.concurrent.FutureTask<Void>(() -> {
                try (var nested = ProductToolEvidenceScope.open()) {
                    ProductToolEvidenceScope.record("其他用户商品 500 元");
                    assertTrue(nested.hasEvidence());
                }
                return null;
            });
            var other = new Thread(task);
            other.start();
            task.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(scope.hasEvidence());
        }
    }

    @Test void nestedScopeRestoresItsCaller() {
        try (var outer = ProductToolEvidenceScope.open()) {
            ProductToolEvidenceScope.record("外层商品");
            try (var inner = ProductToolEvidenceScope.open()) {
                ProductToolEvidenceScope.record("内层商品");
                assertFalse(inner.combine("").contains("外层"));
            }
            ProductToolEvidenceScope.record("外层库存");
            assertFalse(outer.combine("").contains("内层"));
            assertTrue(outer.combine("").contains("外层库存"));
        }
    }
}
