package com.example.smartassistant.router.service.agent;

import com.example.smartassistant.router.model.DiscoveredAgent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDiscoveryAliasTest {
    @Test
    void normalizesKnownAgentAliases() {
        assertEquals("general", AgentDiscoveryService.canonicalAgentName("general_chat"));
        assertEquals("general", AgentDiscoveryService.canonicalAgentName("general-agent-service"));
        assertEquals("product", AgentDiscoveryService.canonicalAgentName("product_agent"));
        assertEquals("order", AgentDiscoveryService.canonicalAgentName("order-service"));
    }

    @Test
    void matchesRoutingAliasAgainstMetadataAndServiceName() {
        AgentDiscoveryService service = new AgentDiscoveryService(null, null, null);
        DiscoveredAgent agent = new DiscoveredAgent();
        agent.setAgentName("general_chat");
        agent.setServiceName("general-agent-service");
        assertTrue(service.matchesAgentName(agent, "general"));
    }
}
