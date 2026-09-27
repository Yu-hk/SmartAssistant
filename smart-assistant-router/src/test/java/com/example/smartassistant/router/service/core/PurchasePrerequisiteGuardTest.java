package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.agent.protocol.ClarificationRequest;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.router.model.SubTaskResult;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PurchasePrerequisiteGuardTest {
    private static SubTaskResult product(int count, String reason) {
        var result = new SubTaskResult("p", "商品查询", "product", "没有符合条件的商品", true);
        result.setDomainQuality(DomainQualityResult.pass(1, reason));
        result.setStructuredData(Map.of("productCount", count));
        return result;
    }

    private static SubTaskResult orderForm(String operation) {
        var result = new SubTaskResult("o", "下单准备", "order", "请补充收货地址", true);
        result.setDomainQuality(DomainQualityResult.pass(1, "ORDER_INPUT_CLARIFICATION"));
        result.setStructuredData(Map.of(ClarificationRequest.DATA_KEY,
                new ClarificationRequest("order", operation,
                        List.of("CREATE_ORDER".equals(operation) ? "shippingAddress" : "orderNumber")).toMap()));
        return result;
    }

    @Test void noEligibleProductSuppressesCheckoutFormAndItsReply() {
        var product = product(0, "EMPTY_PRODUCT_CATALOG");
        var order = orderForm("CREATE_ORDER");
        var visible = PurchasePrerequisiteGuard.visibleResults(List.of(product, order));
        assertEquals(List.of(product), visible);
        assertNull(DomainClarificationRelay.select(visible));
        assertFalse(RouteExecutionService.mergeOrderPreparationResults(visible).contains("收货地址"));
    }

    @Test void positiveCandidateKeepsCheckoutForm() {
        var product = product(1, "PRODUCT_DISCOVERY_DATA");
        var order = orderForm("CREATE_ORDER");
        assertEquals(List.of(product, order), PurchasePrerequisiteGuard.visibleResults(List.of(product, order)));
    }

    @Test void unrelatedOrderOperationIsNotDiscarded() {
        var product = product(0, "NO_ELIGIBLE_VERIFIED_PRODUCT");
        var order = orderForm("QUERY_ORDER");
        assertEquals(List.of(product, order), PurchasePrerequisiteGuard.visibleResults(List.of(product, order)));
    }

    @Test void unverifiedEmptyCatalogDoesNotSuppressForm() {
        var product = product(0, "NOT_EVALUATED");
        var order = orderForm("CREATE_ORDER");
        assertEquals(List.of(product, order), PurchasePrerequisiteGuard.visibleResults(List.of(product, order)));
    }
}
