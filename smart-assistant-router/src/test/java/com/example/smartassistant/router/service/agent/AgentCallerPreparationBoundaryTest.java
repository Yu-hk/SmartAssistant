package com.example.smartassistant.router.service.agent;

import com.example.smartassistant.common.agent.protocol.AgentExecutionRequest;
import com.example.smartassistant.router.model.DiscoveredAgent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentCallerPreparationBoundaryTest {
    @Test void preparation404NeverFallsBackToLegacyModelEndpoint() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var protocolCalls = new AtomicInteger();
        var legacyCalls = new AtomicInteger();
        server.createContext("/internal/agents/order/execute", exchange -> {
            protocolCalls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/", exchange -> {
            legacyCalls.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            var negotiator = mock(AgentVersionNegotiator.class);
            var agent = new DiscoveredAgent();
            agent.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
            when(negotiator.selectCompatibleAgent(anyString(), anyString(), anyString())).thenReturn(agent);
            var caller = new AgentCallerService(mock(AgentDiscoveryService.class), negotiator, null, null);
            for (String operation : List.of("CLARIFY_INPUT", "PREPARE_FALLBACK")) {
                var response = caller.callAgentAndExtractTitles("order", new AgentExecutionRequest("1.0", "test", "prepare", "12",
                        operation, "取消订单 ORD-1", Map.of(), List.of(), List.of(), null, null));
                assertTrue(response.getDomainQuality().isFail());
            }
            assertEquals(2, protocolCalls.get());
            assertEquals(0, legacyCalls.get());
        } finally { server.stop(0); }
    }
}
