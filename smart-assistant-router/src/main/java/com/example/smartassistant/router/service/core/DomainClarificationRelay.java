package com.example.smartassistant.router.service.core;

import com.example.smartassistant.common.agent.protocol.ClarificationRequest;
import com.example.smartassistant.router.model.SubTaskResult;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import java.util.*;

/** Routing transports contracts, it neither invents fields nor combines independent forms. */
final class DomainClarificationRelay {
    private DomainClarificationRelay() { }
    static ClarificationRequest select(List<SubTaskResult> results) {
        if (results == null || !ResultMerger.requiredFailures(results).isEmpty()
                || results.stream().anyMatch(r -> r.getSystemNodeType() == SubTaskResult.SystemNodeType.APPROVAL)) return null;
        Set<ClarificationRequest> contracts = new LinkedHashSet<>();
        for (var result : results) {
            if (!result.isSuccess() || result.getDomainQuality() == null || !result.getDomainQuality().isPass()) continue;
            var contract = ClarificationRequest.read(result.getStructuredData().get(ClarificationRequest.DATA_KEY));
            if (contract != null && contract.domain().equals(AgentDiscoveryService.canonicalAgentName(result.getAgentName())))
                contracts.add(contract);
        }
        return contracts.size() == 1 ? contracts.iterator().next() : null;
    }
}
