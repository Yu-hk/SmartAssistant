package com.example.smartassistant.benchmark;

import com.example.smartassistant.service.graph.ProductGraphService;
import com.example.smartassistant.service.graph.ProductGraphSource;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3 商品知识图谱服务基准测试。
 */
@DisplayName("[P3] ProductGraphService 商品图谱测试")
class ProductGraphBenchmarkTest {

    private ProductGraphService graphService;

    @BeforeEach
    void setUp() {
        ProductGraphSource source = () -> new ProductGraphSource.GraphSnapshot(
                List.of(
                        new ProductGraphSource.ProductNode("IPHONE-15-PRO", "iPhone 15 Pro", "手机", "Apple"),
                        new ProductGraphSource.ProductNode("IPHONE-16-PRO", "iPhone 16 Pro", "手机", "Apple"),
                        new ProductGraphSource.ProductNode("AIRPODS-PRO", "AirPods Pro", "耳机", "Apple"),
                        new ProductGraphSource.ProductNode("APPLE-WATCH-U2", "Apple Watch Ultra 2", "智能手表", "Apple")),
                List.of(
                        new ProductGraphSource.ProductRelation("IPHONE-15-PRO", "IPHONE-16-PRO", "SAME_CATEGORY", 0.95),
                        new ProductGraphSource.ProductRelation("IPHONE-15-PRO", "AIRPODS-PRO", "ACCESSORY", 0.85),
                        new ProductGraphSource.ProductRelation("IPHONE-15-PRO", "APPLE-WATCH-U2", "COMPLEMENT", 0.70)));
        graphService = new ProductGraphService(source);
        graphService.init();
    }

    @Test
    @DisplayName("图谱初始化：加载数据源节点")
    void testGraphInitialization() {
        assertEquals(4, graphService.getNodeCount());
        assertEquals(3, graphService.getEdgeCount());
    }

    @Test
    @DisplayName("同类推荐：iPhone 15 Pro 应推荐 iPhone 16 Pro")
    void testSameCategoryRecommendation() {
        var results = graphService.querySameCategory("IPHONE-15-PRO", 5);
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(r ->
                r.getProductName().contains("iPhone 16")),
                "同类推荐应包含 iPhone 16 Pro");
    }

    @Test
    @DisplayName("配件推荐：iPhone 15 Pro 应推荐 AirPods Pro")
    void testAccessoryRecommendation() {
        var results = graphService.queryAccessories("IPHONE-15-PRO", 5);
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(r ->
                r.getProductName().contains("AirPods")),
                "配件推荐应包含 AirPods Pro");
    }

    @Test
    @DisplayName("商品匹配：查询文本应能匹配到商品编码")
    void testProductMatching() {
        String code = graphService.matchProduct("推荐一款 iPhone 15 Pro 的配件");
        assertEquals("IPHONE-15-PRO", code);
    }

    @Test
    @DisplayName("综合推荐：合并所有关系类型")
    void testRecommendations() {
        var results = graphService.queryRecommendations("IPHONE-15-PRO", 5);
        assertEquals(3, results.size());
        // 验证按得分降序排列
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i-1).getRelevanceScore() >= results.get(i).getRelevanceScore(),
                    "推荐结果应按相关性得分降序排列");
        }
    }

    @Test
    @DisplayName("性能基准：10000 次推荐查询延迟")
    void testQueryLatency() {
        int iterations = 10000;
        long totalNanos = 0;

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            var results = graphService.queryRecommendations("IPHONE-15-PRO", 3);
            totalNanos += System.nanoTime() - start;
            assertNotNull(results);
        }

        double avgUs = (double) totalNanos / iterations / 1_000;
        System.out.printf("[P3 Graph] 10000 次推荐查询，平均 %.3f μs\n", avgUs);
        assertTrue(avgUs <= 50, "图查询延迟应 ≤ 50μs（纯内存操作）");
    }
}
