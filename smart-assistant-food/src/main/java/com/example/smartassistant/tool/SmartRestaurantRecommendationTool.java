package com.example.smartassistant.tool;

import com.example.smartassistant.service.RestaurantReviewSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 智能餐厅推荐工具（基于 RAG）
 * 
 * <p>功能：</p>
 * <ul>
 *     <li>基于用户评价的语义搜索推荐餐厅</li>
 *     <li>支持多维度过滤（城市、菜系、价格、评分）</li>
 *     <li>理解自然语言查询意图</li>
 * </ul>
 * 
 * <p>使用示例：</p>
 * <ul>
 *     <li>"我想找一家环境好的西餐厅，人均200左右"</li>
 *     <li>"成都有什么适合家庭聚会的川菜馆？"</li>
 *     <li>"推荐北京评分高的烤鸭店"</li>
 * </ul>
 */
@Slf4j
@Component
public class SmartRestaurantRecommendationTool {
    
    private final RestaurantReviewSearchService searchService;
    
    public SmartRestaurantRecommendationTool(RestaurantReviewSearchService searchService) {
        this.searchService = searchService;
        log.info("[SmartRestaurantTool] 初始化完成");
    }
    
    /**
     * 智能推荐餐厅（主入口）
     * 
     * @param query 用户查询（自然语言描述需求）
     * @param city 城市（可选，如果查询中未提及）
     * @param cuisineType 菜系类型（可选）
     * @param maxPrice 最高人均价格（可选）
     * @param minRating 最低评分要求（可选，0-5）
     * @return 餐厅推荐列表
     */
    @Tool(description = "基于用户评价智能推荐餐厅。支持自然语言查询，如'环境好的西餐厅'、'适合家庭聚会的川菜馆'等。" +
            "可以指定城市、菜系、价格范围、最低评分等条件。")
    public String recommendRestaurants(String query,
                                       String city,
                                       String cuisineType,
                                       Double maxPrice,
                                       Double minRating) {
        long startTime = System.currentTimeMillis();
        log.info("[SmartRestaurantTool] 开始推荐: query={}, city={}, cuisine={}, maxPrice={}, minRating={}",
                query, city, cuisineType, maxPrice, minRating);
        
        try {
            // 执行语义搜索
            List<RestaurantReviewSearchService.RestaurantRecommendation> results = 
                searchService.searchRestaurants(query, city, cuisineType, maxPrice, minRating);
            
            if (results.isEmpty()) {
                return formatNoResultsMessage(query, city, cuisineType);
            }
            
            // 格式化输出
            String result = formatRecommendations(results, query);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("[SmartRestaurantTool] 推荐完成: 找到{}个结果, 耗时={}ms", 
                    results.size(), duration);
            
            return result;
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[SmartRestaurantTool] 推荐失败: {}", e.getMessage(), e);
            return "抱歉，餐厅推荐服务暂时不可用：" + e.getMessage();
        }
    }
    
    /**
     * 获取热门餐厅
     * 
     * @param city 城市
     * @param cuisineType 菜系（可选）
     * @param limit 返回数量（默认5）
     * @return 热门餐厅列表
     */
    @Tool(description = "获取某个城市的热门餐厅排行榜，按评分排序。可指定菜系类型。")
    public String getPopularRestaurants(String city,
                                        String cuisineType,
                                        Integer limit) {
        if (city == null || city.isEmpty()) {
            return "请指定城市名称，例如：北京、成都、广州";
        }
        
        int topN = (limit != null && limit > 0) ? limit : 5;
        
        log.info("[SmartRestaurantTool] 获取热门餐厅: city={}, cuisine={}, limit={}",
                city, cuisineType, topN);
        
        try {
            List<RestaurantReviewSearchService.RestaurantRecommendation> results = 
                searchService.getPopularRestaurants(city, cuisineType, topN);
            
            if (results.isEmpty()) {
                return String.format("在%s暂未找到%s餐厅数据", city, 
                        cuisineType != null ? cuisineType : "");
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("🏆 【").append(city).append("】热门餐厅排行榜\n");
            if (cuisineType != null && !cuisineType.isEmpty()) {
                sb.append("菜系：").append(cuisineType).append("\n");
            }
            sb.append("\n");
            
            for (int i = 0; i < results.size(); i++) {
                var rec = results.get(i);
                sb.append((i + 1)).append(". ").append(rec.formatToString()).append("\n");
            }
            
            return sb.toString();
            
        } catch (Exception e) {
            log.error("[SmartRestaurantTool] 获取热门餐厅失败: {}", e.getMessage(), e);
            return "抱歉，获取热门餐厅失败：" + e.getMessage();
        }
    }
    
    /**
     * 格式化推荐结果
     */
    private String formatRecommendations(List<RestaurantReviewSearchService.RestaurantRecommendation> results,
                                          String query) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("🍽️ 为您找到以下餐厅推荐（基于您的需求：\"").append(query).append("\"）\n\n");
        
        for (int i = 0; i < results.size(); i++) {
            var rec = results.get(i);
            sb.append("--- 推荐 ").append(i + 1).append(" ---\n");
            sb.append(rec.formatToString());
            sb.append("\n");
        }
        
        sb.append("\n💡 提示：您可以进一步缩小范围，例如指定城市、菜系或价格区间");
        
        return sb.toString();
    }
    
    /**
     * 格式化无结果消息
     */
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
        sb.append("1. 尝试放宽筛选条件（如提高价格上限、降低评分要求）\n");
        sb.append("2. 更换搜索关键词，例如：\"环境好\"、\"性价比高\"、\"适合约会\"\n");
        sb.append("3. 查看热门餐厅排行榜，看看其他人喜欢去哪里\n");
        
        return sb.toString();
    }
}
