package com.example.smartassistant.recommend.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feign 熔断降级工厂单测。
 * <p>验证下游（Product / Order）服务不可用时，Fallback 工厂返回<b>安全空值</b>，
 * 绝不向上游 {@code RecommendService} 抛出运行时异常，从而触发热门兜底而非整体失败。</p>
 */
@DisplayName("[P3] Feign 熔断降级工厂测试")
class FeignFallbackFactoryTest {

    @Test
    @DisplayName("Product 降级：所有方法返回空且绝不抛异常")
    void productFallbackReturnsEmptySafely() {
        ProductFeignClientFallbackFactory factory = new ProductFeignClientFallbackFactory();
        ProductFeignClient client = factory.create(new RuntimeException("product down"));

        assertNotNull(client);
        assertDoesNotThrow(() -> {
            List<Map<String, String>> all = client.getAllProducts();
            List<Map<String, Object>> recs = client.getProductRecommendations("ANY-CODE");
            String info = client.getProductInfo("ANY-CODE");
            assertEquals(0, all.size(), "getAllProducts 应返回空列表");
            assertEquals(0, recs.size(), "getProductRecommendations 应返回空列表");
            assertEquals("", info, "getProductInfo 应返回空字符串");
        });
    }

    @Test
    @DisplayName("Product 降级：create(null) 同样安全（cause 可能为 null）")
    void productFallbackHandlesNullCause() {
        ProductFeignClient client = new ProductFeignClientFallbackFactory().create(null);
        assertNotNull(client);
        assertTrue(client.getAllProducts().isEmpty());
        assertTrue(client.getProductRecommendations("X").isEmpty());
    }

    @Test
    @DisplayName("Order 降级：所有方法返回空且绝不抛异常")
    void orderFallbackReturnsEmptySafely() {
        OrderFeignClientFallbackFactory factory = new OrderFeignClientFallbackFactory();
        OrderFeignClient client = factory.create(new RuntimeException("order down"));

        assertNotNull(client);
        assertDoesNotThrow(() -> {
            List<String> purchased = client.getUserPurchasedProducts(1L);
            List<Map<String, Object>> orders = client.getUserOrders(1L);
            assertEquals(0, purchased.size(), "getUserPurchasedProducts 应返回空列表");
            assertEquals(0, orders.size(), "getUserOrders 应返回空列表");
        });
    }

    @Test
    @DisplayName("Order 降级：create(null) 同样安全")
    void orderFallbackHandlesNullCause() {
        OrderFeignClient client = new OrderFeignClientFallbackFactory().create(null);
        assertNotNull(client);
        assertTrue(client.getUserPurchasedProducts(99L).isEmpty());
    }
}
