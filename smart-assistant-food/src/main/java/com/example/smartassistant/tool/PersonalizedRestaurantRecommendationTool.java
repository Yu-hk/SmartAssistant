package com.example.smartassistant.tool;

import com.example.smartassistant.service.core.ABTestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 个性化餐厅推荐工具（带 A/B 测试）
 * 
 * <p>功能：</p>
 * <ul>
 *     <li>自动将用户分配到不同测试组</li>
 *     <li>根据测试组执行不同的推荐策略</li>
 *     <li>记录用户交互事件用于效果分析</li>
 * </ul>
 */
@Slf4j
@Component
public class PersonalizedRestaurantRecommendationTool {
    
    private final ABTestService abTestService;
    
    public PersonalizedRestaurantRecommendationTool(ABTestService abTestService) {
        this.abTestService = abTestService;
        log.info("[PersonalizedRecommendationTool] 初始化完成");
    }
    
    /**
     * 个性化推荐餐厅（带 A/B 测试）
     * 
     * @param userId 用户ID
     * @param query 查询描述
     * @param city 城市
     * @param cuisineType 菜系
     * @param maxPrice 最高人均价格
     * @param minRating 最低评分
     * @return 推荐结果
     */
    @Tool(description = "基于用户画像和协同过滤的个性化餐厅推荐。" +
            "会自动进行 A/B 测试以优化推荐效果。" +
            "支持自然语言查询，如'环境好的西餐厅'、'适合家庭聚会的川菜馆'等。")
    public String recommendPersonalizedRestaurants(
            Long userId,
            String query,
            String city,
            String cuisineType,
            Double maxPrice,
            Double minRating) {
        
        long startTime = System.currentTimeMillis();
        log.info("[PersonalizedTool] 开始个性化推荐: userId={}, query={}", userId, query);
        
        try {
            // 执行带 A/B 测试的推荐
            ABTestService.RecommendationResult result = 
                abTestService.recommendWithABTest(
                    userId, query, city, cuisineType, maxPrice, minRating
                );
            
            List<com.example.smartassistant.service.core.HybridRecommendationService.HybridRecommendation> recommendations = 
                result.getRecommendations();
            
            if (recommendations.isEmpty()) {
                return formatNoResultsMessage(query, city, cuisineType);
            }
            
            // 格式化输出
            StringBuilder sb = new StringBuilder();
            sb.append("🎯 为您个性化推荐以下餐厅（测试组：").append(result.getTestGroup()).append("）\n\n");
            
            for (int i = 0; i < recommendations.size(); i++) {
                var rec = recommendations.get(i);
                sb.append("--- 推荐 ").append(i + 1).append(" ---\n");
                sb.append(rec.formatToString()).append("\n\n");
                
                // 注意：实际点击事件需要前端上报
                // abTestService.recordClick(String.valueOf(userId), rec.getRestaurantId(), i);
            }
            
            sb.append("\n💡 提示：您的反馈将帮助我们优化推荐算法！");
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("[PersonalizedTool] 推荐完成: 找到{}个结果, 耗时={}ms", 
                    recommendations.size(), duration);
            
            return sb.toString();
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[PersonalizedTool] 推荐失败: {}", e.getMessage(), e);
            return "抱歉，个性化推荐服务暂时不可用：" + e.getMessage();
        }
    }
    
    /**
     * 查看 A/B 测试结果
     * 
     * @param days 统计天数
     * @return 测试结果
     */
    @Tool(description = "查看 A/B 测试结果统计，对比不同推荐算法的效果。")
    public String getABTestResults(Integer days) {
        int statDays = (days != null && days > 0) ? days : 7;
        
        log.info("[PersonalizedTool] 获取 A/B 测试结果: days={}", statDays);
        
        try {
            Map<String, ABTestService.TestMetrics> results = 
                abTestService.getTestResults(statDays);
            
            if (results.isEmpty()) {
                return "暂无足够的 A/B 测试数据";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("📊 A/B 测试结果（最近").append(statDays).append("天）\n\n");
            
            for (var entry : results.entrySet()) {
                sb.append(entry.getValue().formatToString()).append("\n\n");
            }
            
            // 找出最佳组
            String bestGroup = abTestService.getBestPerformingGroup(statDays);
            sb.append("🏆 表现最佳的测试组：").append(bestGroup).append("\n");
            
            return sb.toString();
            
        } catch (Exception e) {
            log.error("[PersonalizedTool] 获取 A/B 测试结果失败: {}", e.getMessage(), e);
            return "抱歉，获取测试结果失败：" + e.getMessage();
        }
    }
    
    private String formatNoResultsMessage(String query, String city, String cuisineType) {
        StringBuilder sb = new StringBuilder();
        sb.append("😅 抱歉，暂未找到符合您需求的餐厅\n\n");
        
        if (city != null && !city.isEmpty()) {
            sb.append("城市：").append(city).append("\n");
        }
        if (cuisineType != null && !cuisineType.isEmpty()) {
            sb.append("菜系：").append(cuisineType).append("\n");
        }
        
        sb.append("\n建议您：\n");
        sb.append("1. 尝试放宽筛选条件\n");
        sb.append("2. 更换搜索关键词\n");
        sb.append("3. 查看热门餐厅排行榜\n");
        
        return sb.toString();
    }
}
