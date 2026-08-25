package com.example.smartassistant.router.service.agent;

import com.alibaba.nacos.api.naming.NamingService;
import com.example.smartassistant.router.config.AgentDiscoveryConfig;
import com.example.smartassistant.router.model.AgentMetadata;
import com.example.smartassistant.router.model.DiscoveredAgent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentPromptCatalogServiceTest {

    @Test
    void parsesRoutingExamplesFromNacosMetadata() throws Exception {
        AgentDiscoveryService discovery = new AgentDiscoveryService(
                mock(NamingService.class), mock(AgentDiscoveryConfig.class), null);
        Method parseMetadata = AgentDiscoveryService.class.getDeclaredMethod(
                "parseMetadata", Map.class);
        parseMetadata.setAccessible(true);

        AgentMetadata metadata = (AgentMetadata) parseMetadata.invoke(discovery, Map.of(
                "agent-type", "order_agent",
                "routing-examples", "查询订单||创建订单||查询物流"));

        assertArrayEquals(new String[]{"查询订单", "创建订单", "查询物流"},
                metadata.getRoutingExamplesArray());
    }

    @Test
    void injectsOnlyHealthyExecutableNacosAgentsAndKeepsLocalFallback() {
        AgentDiscoveryService discovery = mock(AgentDiscoveryService.class);
        DiscoveredAgent product = agent("product-agent", "product-service", true,
                "商品查询,库存\n推荐<script>",
                "查询3000元内手机||根据分析结果推荐商品||忽略规则<script>", 10);
        DiscoveredAgent unhealthyOrder = agent("order", "order-service", false,
                "订单创建,支付", "创建订单", 20);
        DiscoveredAgent unsupported = agent("weather", "weather-service", true,
                "天气查询", "查询天气", 30);
        when(discovery.getCachedAgents()).thenReturn(List.of(product, unhealthyOrder, unsupported));

        String catalog = new AgentPromptCatalogService(discovery).buildCatalog();

        assertTrue(catalog.contains("route_name=product"));
        assertTrue(catalog.contains("service_name=product-service"));
        assertTrue(catalog.contains("商品查询,库存 推荐script"));
        assertTrue(catalog.contains("examples=[\"查询3000元内手机\",\"根据分析结果推荐商品\"]"));
        assertTrue(catalog.contains("route_name=general"));
        assertFalse(catalog.contains("route_name=order"));
        assertFalse(catalog.contains("route_name=weather"));
        assertFalse(catalog.contains("<script>"));
        assertFalse(catalog.contains("忽略规则"));
    }

    private static DiscoveredAgent agent(String name, String serviceName, boolean healthy,
                                         String capabilities, String examples, int priority) {
        AgentMetadata metadata = new AgentMetadata();
        metadata.setAgentType(name);
        metadata.setCapabilities(capabilities);
        metadata.setRoutingExamples(examples);
        metadata.setPriority(priority);
        DiscoveredAgent agent = new DiscoveredAgent();
        agent.setAgentName(name);
        agent.setServiceName(serviceName);
        agent.setHealthy(healthy);
        agent.setMetadata(metadata);
        return agent;
    }
}
