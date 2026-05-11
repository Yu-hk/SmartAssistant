package com.example.smartassistant.service.core;

import com.example.smartassistant.service.search.RestaurantReviewSearchService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A/B 测试服务 - 对比不同推荐算法效果
 * 
 * <p>测试组：</p>
 * <ul>
 *     <li>Control（对照组）: 纯 RAG 推荐</li>
 *     <li>Variant A（实验组A）: 混合推荐（RAG 60% + CF 40%）</li>
 *     <li>Variant B（实验组B）: 混合推荐（RAG 40% + CF 60%）</li>
 * </ul>
 * 
 * <p>核心指标：</p>
 * <ul>
 *     <li>CTR（点击率）- 用户点击推荐结果的比例</li>
 *     <li>Conversion Rate（转化率）- 用户采纳建议的比例</li>
 *     <li>Avg Rating（平均评分）- 推荐餐厅的平均评分</li>
 *     <li>Diversity（多样性）- 推荐结果的多样性</li>
 * </ul>
 */
@Slf4j
@Service
public class ABTestService {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private HybridRecommendationService hybridService;
    
    @Autowired
    private RestaurantReviewSearchService ragService;
    
    // 用户分组缓存（userId -> testGroup）
    private final Map<String, String> userGroupCache = new ConcurrentHashMap<>();
    
    // 测试组配置
    private static final List<TestGroup> TEST_GROUPS = Arrays.asList(
        new TestGroup("control", "纯RAG", 0.34),    // 34% 流量
        new TestGroup("variant_a", "混合推荐A", 0.33), // 33% 流量
        new TestGroup("variant_b", "混合推荐B", 0.33)  // 33% 流量
    );
    
    /**
     * 为用户分配测试组
     * 
     * @param userId 用户ID
     * @return 测试组名称
     */
    public String assignUserToGroup(String userId) {
        // 检查缓存
        if (userGroupCache.containsKey(userId)) {
            return userGroupCache.get(userId);
        }
        
        // 基于用户 ID 哈希分配（保证同一用户始终在同一组）
        int hash = Math.abs(userId.hashCode());
        double normalizedHash = (hash % 10000) / 10000.0;  // 归一化到 0-1
        
        String assignedGroup = "control";
        double cumulativeProbability = 0;
        
        for (TestGroup group : TEST_GROUPS) {
            cumulativeProbability += group.getTrafficAllocation();
            if (normalizedHash < cumulativeProbability) {
                assignedGroup = group.getName();
                break;
            }
        }
        
        // 缓存分配结果
        userGroupCache.put(userId, assignedGroup);
        
        log.info("[ABTest] 用户 {} 分配到组: {}", userId, assignedGroup);
        
        return assignedGroup;
    }
    
    /**
     * 根据测试组执行推荐
     * 
     * @param userId 用户ID
     * @param query 查询文本
     * @param city 城市
     * @param cuisineType 菜系
     * @param maxPrice 最高价格
     * @param minRating 最低评分
     * @return 推荐结果
     */
    public RecommendationResult recommendWithABTest(
            Long userId,
            String query,
            String city,
            String cuisineType,
            Double maxPrice,
            Double minRating) {
        
        String userIdStr = String.valueOf(userId);
        String testGroup = assignUserToGroup(userIdStr);
        
        log.info("[ABTest] 执行推荐: userId={}, group={}", userId, testGroup);
        
        List<HybridRecommendationService.HybridRecommendation> recommendations = switch (testGroup) {
            case "control" ->
                // 对照组：纯 RAG
                    executeControlGroup(query, city, cuisineType, maxPrice, minRating);
            case "variant_a" ->
                // 实验组A：混合推荐（60/40）
                    executeVariantA(userId, query, city, cuisineType, maxPrice, minRating);
            case "variant_b" ->
                // 实验组B：混合推荐（40/60）
                    executeVariantB(userId, query, city, cuisineType, maxPrice, minRating);
            default -> executeControlGroup(query, city, cuisineType, maxPrice, minRating);
        };

        // 记录曝光事件
        recordImpression(userIdStr, testGroup, recommendations);
        
        return new RecommendationResult(testGroup, recommendations);
    }
    
    /**
     * 记录用户点击事件
     * 
     * @param userId 用户ID
     * @param restaurantId 餐厅ID
     * @param position 推荐位置（从0开始）
     */
    public void recordClick(String userId, String restaurantId, int position) {
        String sql = """
            INSERT INTO ab_test_events (user_id, test_group, restaurant_id, 
                                        event_type, position, created_at)
            VALUES (?, ?, ?, 'click', ?, NOW())
            """;
        
        try {
            String testGroup = userGroupCache.getOrDefault(userId, "control");
            jdbcTemplate.update(sql, userId, testGroup, restaurantId, position);
            
            log.debug("[ABTest] 记录点击: userId={}, restaurantId={}, position={}", 
                    userId, restaurantId, position);
        } catch (Exception e) {
            log.error("[ABTest] 记录点击失败: {}", e.getMessage());
        }
    }
    
    /**
     * 记录转化事件（用户实际前往餐厅）
     */
    public void recordConversion(String userId, String restaurantId) {
        String sql = """
            INSERT INTO ab_test_events (user_id, test_group, restaurant_id, 
                                        event_type, created_at)
            VALUES (?, ?, ?, 'conversion', NOW())
            """;
        
        try {
            String testGroup = userGroupCache.getOrDefault(userId, "control");
            jdbcTemplate.update(sql, userId, testGroup, restaurantId);
            
            log.info("[ABTest] 记录转化: userId={}, restaurantId={}", userId, restaurantId);
        } catch (Exception e) {
            log.error("[ABTest] 记录转化失败: {}", e.getMessage());
        }
    }
    
    /**
     * 获取 A/B 测试结果统计
     * 
     * @param days 统计天数（默认7天）
     * @return 各测试组的指标
     */
    public Map<String, TestMetrics> getTestResults(int days) {
        Map<String, TestMetrics> results = new HashMap<>();
        
        String sql = """
            SELECT
                test_group,
                COUNT(DISTINCT CASE WHEN event_type = 'impression' THEN id END) as impressions,
                COUNT(DISTINCT CASE WHEN event_type = 'click' THEN id END) as clicks,
                COUNT(DISTINCT CASE WHEN event_type = 'conversion' THEN id END) as conversions
            FROM ab_test_events
            WHERE created_at >= NOW() - INTERVAL '? days'
            GROUP BY test_group
            """;
        
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                sql.replace("?", String.valueOf(days))
            );
            
            for (Map<String, Object> row : rows) {
                String group = (String) row.get("test_group");
                long impressions = ((Number) row.get("impressions")).longValue();
                long clicks = ((Number) row.get("clicks")).longValue();
                long conversions = ((Number) row.get("conversions")).longValue();
                
                double ctr = impressions > 0 ? (double) clicks / impressions : 0;
                double conversionRate = clicks > 0 ? (double) conversions / clicks : 0;
                
                results.put(group, new TestMetrics(
                    group,
                    impressions,
                    clicks,
                    conversions,
                    ctr,
                    conversionRate
                ));
            }
            
        } catch (Exception e) {
            log.error("[ABTest] 获取测试结果失败: {}", e.getMessage(), e);
        }
        
        return results;
    }
    
    /**
     * 判断哪个测试组表现更好
     */
    public String getBestPerformingGroup(int days) {
        Map<String, TestMetrics> results = getTestResults(days);
        
        if (results.isEmpty()) {
            return "insufficient_data";
        }
        
        // 按 CTR 排序
        Optional<Map.Entry<String, TestMetrics>> bestGroup = results.entrySet().stream()
            .max((e1, e2) -> Double.compare(
                e1.getValue().getCtr(),
                e2.getValue().getCtr()
            ));
        
        return bestGroup.map(Map.Entry::getKey).orElse("control");
    }
    
    // ==================== 私有方法 ====================
    
    private List<HybridRecommendationService.HybridRecommendation> executeControlGroup(
            String query, String city, String cuisineType, Double maxPrice, Double minRating) {
        
        List<RestaurantReviewSearchService.RestaurantRecommendation> ragResults = 
            ragService.searchRestaurants(query, city, cuisineType, maxPrice, minRating);
        
        return ragResults.stream()
            .map(r -> new HybridRecommendationService.HybridRecommendation(
                r.getRestaurantId(),
                r.getRestaurantName(),
                r.getCity(),
                r.getCuisineType(),
                r.getAddress(),
                r.getAvgPrice(),
                r.getRating(),
                r.getSimilarityScore() != null ? r.getSimilarityScore() : 0,
                0.0,
                r.getSimilarityScore() != null ? r.getSimilarityScore() : 0,
                "control"
            ))
            .toList();
    }
    
    private List<HybridRecommendationService.HybridRecommendation> executeVariantA(
            Long userId, String query, String city, String cuisineType, 
            Double maxPrice, Double minRating) {
        
        return hybridService.recommendHybrid(userId, query, city, cuisineType, maxPrice, minRating);
    }
    
    private List<HybridRecommendationService.HybridRecommendation> executeVariantB(
            Long userId, String query, String city, String cuisineType, 
            Double maxPrice, Double minRating) {
        
        // ⚠️ 待 CollaborativeFilteringService 实现后调整权重：RAG 40% + CF 60%
        // 当前 recommendHybrid() 仅执行 RAG，CF 功能尚未实现（见 HybridRecommendationService 注释）
        return hybridService.recommendHybrid(userId, query, city, cuisineType, maxPrice, minRating);
    }
    
    private void recordImpression(String userId, String testGroup, 
                                   List<HybridRecommendationService.HybridRecommendation> recommendations) {
        String sql = """
            INSERT INTO ab_test_events (user_id, test_group, restaurant_id, 
                                        event_type, position, created_at)
            VALUES (?, ?, ?, 'impression', ?, NOW())
            """;
        
        try {
            for (int i = 0; i < recommendations.size(); i++) {
                var rec = recommendations.get(i);
                jdbcTemplate.update(sql, userId, testGroup, rec.getRestaurantId(), i);
            }
        } catch (Exception e) {
            log.error("[ABTest] 记录曝光失败: {}", e.getMessage());
        }
    }
    
    // ==================== 内部类 ====================
    
    @Data
    private static class TestGroup {
        private final String name;
        private final String description;
        private final double trafficAllocation;
    }
    
    @Data
    public static class RecommendationResult {
        private final String testGroup;
        private final List<HybridRecommendationService.HybridRecommendation> recommendations;
    }
    
    @Data
    public static class TestMetrics {
        private final String testGroup;
        private final long impressions;
        private final long clicks;
        private final long conversions;
        private final double ctr;
        private final double conversionRate;
        
        public String formatToString() {
            return String.format(
                    """
                            📊 测试组: %s
                               曝光量: %d
                               点击量: %d
                               转化量: %d
                               CTR: %.2f%%
                               转化率: %.2f%%""",
                testGroup, impressions, clicks, conversions,
                ctr * 100, conversionRate * 100
            );
        }
    }
}
