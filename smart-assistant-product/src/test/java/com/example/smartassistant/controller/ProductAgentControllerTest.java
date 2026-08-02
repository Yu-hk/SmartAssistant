package com.example.smartassistant.controller;

import com.example.smartassistant.service.agent.StreamingProductAgentService;
import com.example.smartassistant.service.graph.ProductGraphService;
import com.example.smartassistant.spi.ProductBackend;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ProductAgentControllerTest {

    @Test
    void structuredProductQuery_shouldReadBackendWithoutCallingLlm() {
        ProductBackend backend = mock(ProductBackend.class);
        ProductGraphService graph = mock(ProductGraphService.class);
        StreamingProductAgentService agent = mock(StreamingProductAgentService.class);
        when(graph.matchProduct(anyString())).thenReturn("IPHONE-15-PRO");
        when(backend.queryProductInfo("IPHONE-15-PRO"))
                .thenReturn("iPhone 15 Pro\n价格：8999 元\n库存：充足\n颜色：原色钛金属");

        ProductAgentController controller = new ProductAgentController(backend, graph, agent);
        String result = controller.processQuestion(Map.of(
                "question", "iPhone 15 Pro 的价格、库存和颜色是什么？",
                "requestId", "test-product-1"));

        assertEquals("iPhone 15 Pro\n价格：8999 元\n库存：充足\n颜色：原色钛金属", result);
        verify(agent, never()).execute(anyString());
    }
}
