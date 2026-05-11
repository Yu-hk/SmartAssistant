package com.example.smartassistant.agent;

import com.example.smartassistant.common.correction.CorrectionService;
import com.example.smartassistant.tool.FoodRecommendationTool;
import com.example.smartassistant.tool.PersonalizedRestaurantRecommendationTool;
import com.example.smartassistant.tool.SmartRestaurantRecommendationTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Food Agent 工具集
 * 聚合所有美食相关工具，供 ReAct Agent 调用
 */
@Slf4j
@Component
public class FoodAgentTools {

    private final FoodRecommendationTool foodRecommendationTool;
    private final SmartRestaurantRecommendationTool smartRestaurantRecommendationTool;
    private final PersonalizedRestaurantRecommendationTool personalizedRestaurantRecommendationTool;
    private final CorrectionService correctionService;

    public FoodAgentTools(
            FoodRecommendationTool foodRecommendationTool,
            SmartRestaurantRecommendationTool smartRestaurantRecommendationTool,
            PersonalizedRestaurantRecommendationTool personalizedRestaurantRecommendationTool,
            CorrectionService correctionService) {
        this.foodRecommendationTool = foodRecommendationTool;
        this.smartRestaurantRecommendationTool = smartRestaurantRecommendationTool;
        this.personalizedRestaurantRecommendationTool = personalizedRestaurantRecommendationTool;
        this.correctionService = correctionService;
    }

    /**
     * 查询指定地点的特色菜
     */
    @Tool(description = "查询指定地点（省份或城市）的特色菜和美食文化")
    public String querySpecialtyCuisine(@ToolParam(description = "地点名称，如'河北'、'北京'、'杭州'等") String location) {
        log.info("[FoodAgentTools] 调用 querySpecialtyCuisine: location={}", location);
        return foodRecommendationTool.querySpecialtyCuisine(location);
    }

    /**
     * 基于位置的附近餐厅推荐
     */
    @Tool(description = "根据城市、菜系类型和位置推荐附近的美食餐厅")
    public String recommendNearbyRestaurants(
            @ToolParam(description = "城市名称") String city,
            @ToolParam(description = "菜系类型（可选），如川菜、粤菜、日料等") String cuisineType,
            @ToolParam(description = "坐标（可选），格式：纬度,经度") String coordinates) {
        log.info("[FoodAgentTools] 调用 recommendNearbyRestaurants: city={}, cuisineType={}", city, cuisineType);
        return foodRecommendationTool.recommendFood(city, cuisineType, coordinates);
    }

    /**
     * 智能餐厅推荐（基于 RAG）
     */
    @Tool(description = "基于用户评价语义搜索智能推荐餐厅，支持自然语言查询")
    public String smartRecommendRestaurants(
            @ToolParam(description = "用户需求描述，如'环境好的西餐厅'、'适合家庭聚会的川菜馆'") String query,
            @ToolParam(description = "城市（可选）") String city,
            @ToolParam(description = "菜系类型（可选）") String cuisineType,
            @ToolParam(description = "最高人均价格（可选）") Double maxPrice,
            @ToolParam(description = "最低评分要求（可选，0-5）") Double minRating) {
        log.info("[FoodAgentTools] 调用 smartRecommendRestaurants: query={}, city={}", query, city);
        return smartRestaurantRecommendationTool.recommendRestaurants(query, city, cuisineType, maxPrice, minRating);
    }

    /**
     * 获取热门餐厅排行榜
     */
    @Tool(description = "获取某个城市的热门餐厅排行榜，按评分排序")
    public String getPopularRestaurants(
            @ToolParam(description = "城市名称") String city,
            @ToolParam(description = "菜系类型（可选）") String cuisineType,
            @ToolParam(description = "返回数量（默认5）") Integer limit) {
        log.info("[FoodAgentTools] 调用 getPopularRestaurants: city={}, cuisineType={}", city, cuisineType);
        return smartRestaurantRecommendationTool.getPopularRestaurants(city, cuisineType, limit);
    }

    /**
     * 个性化餐厅推荐（带 A/B 测试）
     */
    @Tool(description = "基于用户画像和协同过滤的个性化餐厅推荐，会自动进行 A/B 测试优化效果")
    public String recommendPersonalizedRestaurants(
            @ToolParam(description = "用户ID") Long userId,
            @ToolParam(description = "查询描述，如'环境好的西餐厅'") String query,
            @ToolParam(description = "城市") String city,
            @ToolParam(description = "菜系类型") String cuisineType,
            @ToolParam(description = "最高人均价格") Double maxPrice,
            @ToolParam(description = "最低评分") Double minRating) {
        log.info("[FoodAgentTools] 调用 recommendPersonalizedRestaurants: userId={}, query={}", userId, query);
        return personalizedRestaurantRecommendationTool.recommendPersonalizedRestaurants(
                userId, query, city, cuisineType, maxPrice, minRating);
    }

    /**
     * 查看 A/B 测试结果
     */
    @Tool(description = "查看 A/B 测试结果统计，对比不同推荐算法的效果")
    public String getABTestResults(@ToolParam(description = "统计天数（默认7天）") Integer days) {
        log.info("[FoodAgentTools] 调用 getABTestResults: days={}", days);
        return personalizedRestaurantRecommendationTool.getABTestResults(days);
    }

    /**
     * 查询历史纠错记录
     */
    @Tool(description = "查询本 Agent 的历史纠错记录。在回答事实性问题前先调用此工具，检查是否有已被用户纠正过的信息，避免重复错误。")
    public String queryCorrections(
            @ToolParam(description = "查询主题，如'北京烤鸭'、'川菜推荐'，空字符串则返回全部", required = false) String topic) {
        log.info("[FoodAgent] 查询修正记录: topic={}", topic);
        String result = correctionService.queryCorrections("food", topic != null ? topic : "");
        if (result.isBlank()) {
            return "未找到相关的修正记录，可以按正常流程回答。";
        }
        return result;
    }
}
