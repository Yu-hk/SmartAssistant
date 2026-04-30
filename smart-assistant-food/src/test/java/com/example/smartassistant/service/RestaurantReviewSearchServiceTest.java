package com.example.smartassistant.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 餐厅评论语义搜索服务测试
 */
@SpringBootTest
class RestaurantReviewSearchServiceTest {
    
    @Autowired
    private RestaurantReviewSearchService searchService;
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @BeforeEach
    void setUp() {
        // 检查数据库连接
        assertNotNull(jdbcTemplate);
    }
    
    @Test
    @DisplayName("测试1: 语义搜索 - 查找环境好的西餐厅")
    void testSearchRestaurants_ByEnvironment() {
        List<RestaurantReviewSearchService.RestaurantRecommendation> results = 
            searchService.searchRestaurants(
                "环境好的西餐厅",
                null,  // 不限城市
                null,  // 不限菜系
                null,  // 不限价格
                null   // 不限评分
            );
        
        assertNotNull(results);
        System.out.println("=== 测试结果：环境好的西餐厅 ===");
        results.forEach(rec -> {
            System.out.println(rec.formatToString());
            System.out.println("---");
        });
    }
    
    @Test
    @DisplayName("测试2: 语义搜索 - 成都适合家庭聚会的川菜馆")
    void testSearchRestaurants_ChengduFamilyFriendly() {
        List<RestaurantReviewSearchService.RestaurantRecommendation> results = 
            searchService.searchRestaurants(
                "适合家庭聚会",
                "成都",
                "川菜",
                150.0,  // 人均不超过150
                4.0     // 评分不低于4.0
            );
        
        assertNotNull(results);
        assertFalse(results.isEmpty(), "应该找到符合条件的餐厅");
        
        System.out.println("=== 测试结果：成都家庭友好川菜馆 ===");
        results.forEach(rec -> {
            System.out.println(rec.formatToString());
            System.out.println("---");
            
            // 验证过滤条件
            assertEquals("成都", rec.getCity());
            assertEquals("川菜", rec.getCuisineType());
            assertTrue(rec.getAvgPrice() <= 150.0);
            assertTrue(rec.getRating() >= 4.0);
        });
    }
    
    @Test
    @DisplayName("测试3: 语义搜索 - 北京高端烤鸭店")
    void testSearchRestaurants_BeijingHighEndDuck() {
        List<RestaurantReviewSearchService.RestaurantRecommendation> results = 
            searchService.searchRestaurants(
                "高端烤鸭店，适合商务宴请",
                "北京",
                null,
                null,
                4.5  // 评分不低于4.5
            );
        
        assertNotNull(results);
        
        System.out.println("=== 测试结果：北京高端烤鸭店 ===");
        results.forEach(rec -> {
            System.out.println(rec.formatToString());
            System.out.println("---");
        });
    }
    
    @Test
    @DisplayName("测试4: 获取热门餐厅 - 成都川菜")
    void testGetPopularRestaurants_ChengduSichuan() {
        List<RestaurantReviewSearchService.RestaurantRecommendation> results = 
            searchService.getPopularRestaurants("成都", "川菜", 3);
        
        assertNotNull(results);
        assertFalse(results.isEmpty(), "应该找到热门餐厅");
        assertTrue(results.size() <= 3, "返回数量不应超过限制");
        
        System.out.println("=== 测试结果：成都热门川菜馆 ===");
        for (int i = 0; i < results.size(); i++) {
            System.out.println((i + 1) + ". " + results.get(i).formatToString());
        }
    }
    
    @Test
    @DisplayName("测试5: 语义搜索 - 性价比高的餐厅")
    void testSearchRestaurants_GoodValue() {
        List<RestaurantReviewSearchService.RestaurantRecommendation> results = 
            searchService.searchRestaurants(
                "性价比高，价格便宜",
                null,
                null,
                50.0,  // 人均不超过50
                null
            );
        
        assertNotNull(results);
        
        System.out.println("=== 测试结果：高性价比餐厅 ===");
        results.forEach(rec -> {
            System.out.println(rec.formatToString());
            System.out.println("---");
        });
    }
    
    @Test
    @DisplayName("测试6: 语义搜索 - 无结果情况")
    void testSearchRestaurants_NoResults() {
        List<RestaurantReviewSearchService.RestaurantRecommendation> results = 
            searchService.searchRestaurants(
                "火星上的餐厅",
                "火星",
                null,
                null,
                null
            );
        
        assertNotNull(results);
        assertTrue(results.isEmpty(), "不应该找到火星上的餐厅");
        
        System.out.println("=== 测试结果：无结果情况 ===");
        System.out.println("正确返回空列表 ✓");
    }
    
    @Test
    @DisplayName("测试7: 验证向量索引是否创建")
    void testVectorIndexExists() {
        String sql = "SELECT indexname FROM pg_indexes WHERE tablename = 'restaurant_reviews_vector'";
        List<String> indexes = jdbcTemplate.queryForList(sql, String.class);
        
        assertNotNull(indexes);
        assertFalse(indexes.isEmpty(), "应该有索引");
        
        boolean hasHnswIndex = indexes.stream()
                .anyMatch(idx -> idx.contains("hnsw"));
        
        assertTrue(hasHnswIndex, "应该有 HNSW 向量索引");
        
        System.out.println("=== 测试结果：向量索引 ===");
        System.out.println("找到的索引：" + indexes);
        System.out.println("HNSW 索引存在 ✓");
    }
    
    @Test
    @DisplayName("测试8: 验证数据已导入")
    void testDataImported() {
        String sql = "SELECT COUNT(*) FROM restaurant_reviews_vector";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        
        assertNotNull(count);
        assertTrue(count > 0, "应该有评论数据");
        
        System.out.println("=== 测试结果：数据导入 ===");
        System.out.println("评论总数：" + count);
        System.out.println("数据已导入 ✓");
    }
    
    @Test
    @DisplayName("测试9: 验转向量已生成")
    void testEmbeddingsGenerated() {
        String sql = "SELECT COUNT(*) FROM restaurant_reviews_vector WHERE embedding IS NOT NULL";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        
        assertNotNull(count);
        
        String totalSql = "SELECT COUNT(*) FROM restaurant_reviews_vector";
        Integer total = jdbcTemplate.queryForObject(totalSql, Integer.class);
        
        System.out.println("=== 测试结果：向量生成 ===");
        System.out.println("总评论数：" + total);
        System.out.println("已向量化：" + count);
        System.out.println("向量化比例：" + (count * 100.0 / total) + "%");
        
        if (count.equals(total)) {
            System.out.println("所有评论已完成向量化 ✓");
        } else {
            System.out.println("⚠️ 还有 " + (total - count) + " 条评论未向量化");
        }
    }
}
