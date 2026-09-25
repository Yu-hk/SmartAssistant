package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.agent.protocol.ClarificationRequest;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Do not request checkout details when the product domain has verified no eligible item. */
final class PurchasePrerequisiteGuard {
    private static final Set<String> EMPTY_REASONS = Set.of(
            "EMPTY_PRODUCT_CATALOG", "NO_ELIGIBLE_VERIFIED_PRODUCT");

    private PurchasePrerequisiteGuard() { }

    static List<SubTaskResult> visibleResults(List<SubTaskResult> results) {
        if (results == null || results.isEmpty()) return List.of();
        boolean positiveCandidate = results.stream().anyMatch(PurchasePrerequisiteGuard::hasCandidate);
        boolean verifiedEmpty = results.stream().anyMatch(PurchasePrerequisiteGuard::verifiedEmpty);
        if (!verifiedEmpty || positiveCandidate) return results;
        return results.stream().filter(result -> !isReadOnlyCreatePreparation(result)).toList();
    }

    private static boolean isProduct(SubTaskResult result) {
        return result != null && "product".equals(AgentDiscoveryService.canonicalAgentName(result.getAgentName()));
    }

    private static boolean hasCandidate(SubTaskResult result) {
        if (!isProduct(result) || !result.isSuccess()) return false;
        Map<String, Object> data = result.getStructuredData();
        Object count = data.get("productCount");
        return count instanceof Number number && number.intValue() > 0
                || data.get("products") instanceof List<?> products && !products.isEmpty();
    }

    private static boolean verifiedEmpty(SubTaskResult result) {
        if (!isProduct(result) || !result.isSuccess() || result.getDomainQuality() == null
                || !result.getDomainQuality().isPass()) return false;
        if (result.getDomainQuality().getReasonCodes().stream().noneMatch(EMPTY_REASONS::contains)) return false;
        Object count = result.getStructuredData().get("productCount");
        return !(count instanceof Number number) || number.intValue() == 0;
    }

    private static boolean isReadOnlyCreatePreparation(SubTaskResult result) {
        if (result == null || !result.isSuccess()
                || !"order".equals(AgentDiscoveryService.canonicalAgentName(result.getAgentName()))) return false;
        if (result.getSystemNodeType() == SubTaskResult.SystemNodeType.ORDER_PREPARATION) return true;
        ClarificationRequest request = ClarificationRequest.read(
                result.getStructuredData().get(ClarificationRequest.DATA_KEY));
        return request != null && "order".equals(request.domain())
                && "CREATE_ORDER".equals(request.operation());
    }
}
