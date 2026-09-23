package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.agent.protocol.ClarificationRequest;
import com.example.smartassistant.common.quality.DomainQualityResult;
import com.example.smartassistant.router.model.SubTaskResult;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DomainClarificationRelayTest {
    private SubTaskResult result(String domain, String operation, String field) {
        var result = new SubTaskResult("n", "test", domain, "请补充信息", true);
        result.setDomainQuality(DomainQualityResult.pass(1, "CLARIFICATION"));
        result.setStructuredData(Map.of("clarificationRequest", new ClarificationRequest(domain, operation, List.of(field)).toMap()));
        return result;
    }
    @Test void rejectsCrossOperationFields() {
        for (String field : List.of("weight", "budget", "orderNumber", "amount"))
            assertThrows(IllegalArgumentException.class, () -> new ClarificationRequest("order", "CREATE_ORDER", List.of(field)));
        assertNull(ClarificationRequest.read(Map.of("domain", "product", "operation", "QUERY_PRODUCT", "fields", List.of("orderNumber"))));
    }
    @Test void relaysOneVerifiedDomainAndDeduplicatesTransitiveResults() {
        var node = result("product", "DISCOVER_PRODUCTS", "weight");
        assertEquals(List.of("weight"), DomainClarificationRelay.select(List.of(node, node)).fields());
        node.setAgentName("order");
        assertNull(DomainClarificationRelay.select(List.of(node)));
    }
    @Test void doesNotCombineIndependentTasksOrApprovalWithForm() {
        var product = result("product", "QUERY_PRODUCT", "product");
        var order = result("order", "TRACK_LOGISTICS", "orderNumber");
        assertNull(DomainClarificationRelay.select(List.of(product, order)));
        order.setSystemNodeType(SubTaskResult.SystemNodeType.APPROVAL);
        assertNull(DomainClarificationRelay.select(List.of(product, order)));
    }
}
