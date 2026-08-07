package com.example.smartassistant.router.service.tool;

import com.example.smartassistant.router.model.AgentMetadata;
import com.example.smartassistant.router.model.DiscoveredAgent;
import com.example.smartassistant.router.service.agent.AgentDiscoveryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingToolCheckerTest {

    @Test
    void usesCapabilitiesDeclaredByActualAgent() {
        AgentDiscoveryService discovery = mock(AgentDiscoveryService.class);
        DiscoveredAgent agent = agent("general_chat", true,
                "chat,qa,image_analysis,image_generation");
        when(discovery.resolveAgent("general")).thenReturn(agent);

        RoutingToolChecker.ToolHealthResult result =
                new RoutingToolChecker(discovery).checkAgentHealth("general");

        assertTrue(result.isHealthy());
        assertTrue(result.getMessage().contains("image_analysis"));
        assertFalse(result.getMessage().contains("queryWeather"));
    }

    @Test
    void rejectsAgentWithoutCapabilityDeclaration() {
        AgentDiscoveryService discovery = mock(AgentDiscoveryService.class);
        when(discovery.resolveAgent("order")).thenReturn(agent("order_agent", true, ""));

        RoutingToolChecker.ToolHealthResult result =
                new RoutingToolChecker(discovery).checkAgentHealth("order");

        assertFalse(result.isHealthy());
        assertTrue(result.getUnhealthyTools().contains("capabilities_not_declared"));
    }

    private static DiscoveredAgent agent(String name, boolean healthy, String capabilities) {
        AgentMetadata metadata = new AgentMetadata();
        metadata.setCapabilities(capabilities);
        DiscoveredAgent agent = new DiscoveredAgent();
        agent.setAgentName(name);
        agent.setServiceName(name + "-service");
        agent.setHealthy(healthy);
        agent.setMetadata(metadata);
        return agent;
    }
}
