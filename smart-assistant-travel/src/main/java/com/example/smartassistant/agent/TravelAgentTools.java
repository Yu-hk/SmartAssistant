package com.example.smartassistant.agent;

import com.example.smartassistant.tools.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Travel Agent 工具集
 * 聚合所有旅行相关工具，供 Agent 调用
 * 
 * 注意：RAG 相关工具（getUserTravelNotes, searchRelevantTravelNotes, getUserTravelPreferences）
 * 已由 TravelRagTool 直接注册，此处仅保留非 RAG 工具的包装
 */
@Slf4j
@Component
public class TravelAgentTools {

    private final AttractionRealtimeTool attractionRealtimeTool;
    private final TravelGuideCrawlerTool travelGuideCrawlerTool;
    private final WeatherTool weatherTool;
    private final LocationTool locationTool;
    private final TravelPlannerTool travelPlannerTool;
    private final SmartTravelPlannerTool smartTravelPlannerTool;

    public TravelAgentTools(
            AttractionRealtimeTool attractionRealtimeTool,
            TravelGuideCrawlerTool travelGuideCrawlerTool,
            WeatherTool weatherTool,
            LocationTool locationTool,
            TravelPlannerTool travelPlannerTool,
            SmartTravelPlannerTool smartTravelPlannerTool) {
        this.attractionRealtimeTool = attractionRealtimeTool;
        this.travelGuideCrawlerTool = travelGuideCrawlerTool;
        this.weatherTool = weatherTool;
        this.locationTool = locationTool;
        this.travelPlannerTool = travelPlannerTool;
        this.smartTravelPlannerTool = smartTravelPlannerTool;
    }

    /**
     * 查询景点实时信息（开放状态、门票、活动、天气影响）
     */
    @Tool(description = "查询景点的实时信息，包括开放状态、门票价格、当前活动、天气影响等。适用于需要最新景点数据时。")
    public String getAttractionRealtimeInfo(
            @ToolParam(description = "景点名称，如'西湖'、'故宫'、'颐和园'", required = true) String attractionName,
            @ToolParam(description = "城市名称，如'杭州'、'北京'", required = true) String city) {
        log.info("[TravelAgent] 查询景点实时信息: name={}, city={}", attractionName, city);
        return attractionRealtimeTool.getAttractionRealtimeInfo(attractionName, city, "");
    }

    /**
     * 查询多个景点的实时信息对比
     */
    @Tool(description = "对比多个景点的实时信息，快速了解各景点的开放状态、门票、活动等差异。")
    public String compareAttractions(
            @ToolParam(description = "景点名称列表，逗号分隔，如'西湖,灵隐寺,宋城'", required = true) String attractions,
            @ToolParam(description = "城市名称", required = true) String city) {
        log.info("[TravelAgent] 对比景点: {}, city={}", attractions, city);
        return attractionRealtimeTool.compareAttractions(attractions, city, "");
    }

    /**
     * 查询天气预报
     */
    @Tool(description = "查询指定城市/地点的天气预报，包括温度、天气状况、穿衣建议等。适用于规划出行。")
    public String getWeather(
            @ToolParam(description = "城市名称，如'杭州'、'北京'", required = true) String city) {
        log.info("[TravelAgent] 查询天气: {}", city);
        return weatherTool.query(city);
    }

    /**
     * 搜索附近的景点
     */
    @Tool(description = "获取当前所在城市的名称（内部工具，用于确定用户位置）")
    public String getCurrentCity() {
        log.info("[TravelAgent] 获取当前城市");
        return locationTool.getCurrentCityName();
    }

    /**
     * 智能规划行程
     * 根据目的地、时间、偏好自动生成行程安排
     */
    @Tool(description = "智能规划旅行行程。根据目的地、天数和偏好生成完整的行程安排，包括每日路线、景点推荐、时间安排等。适用于'帮我规划杭州3日游'、'成都4天亲子游'等场景")
    public String smartPlanTrip(
            @ToolParam(description = "目的地城市，如'杭州'、'成都'", required = true) String destination,
            @ToolParam(description = "游玩天数，如'3'", required = true) String days,
            @ToolParam(description = "旅行偏好或主题，如'亲子游'、'美食之旅'、'文化探索'，为空则通用规划", required = false) String preference) {
        log.info("[TravelAgent] 智能规划行程: dest={}, days={}, pref={}", destination, days, preference);
        return smartTravelPlannerTool.smartPlan(destination, days, preference != null ? preference : "");
    }

    /**
     * 根据天气推荐活动
     */
    @Tool(description = "根据天气预报推荐合适的娱乐活动，包括室内/室外活动建议。")
    public String recommendActivitiesByWeather(
            @ToolParam(description = "城市名称，如'杭州'、'成都'", required = true) String city) {
        log.info("[TravelAgent] 天气推荐活动: {}", city);
        return travelPlannerTool.planTravel("", city);
    }

    /**
     * 爬取攻略网页内容
     * 仅在用户明确提供 URL 时使用
     */
    @Tool(description = "从指定URL爬取攻略内容。仅当用户明确提供URL时才使用。")
    public String crawlTravelGuide(
            @ToolParam(description = "攻略网页URL，必须是公开旅游网站", required = true) String url,
            @ToolParam(description = "当前用户ID", required = false) Long userId) {
        log.info("[TravelAgent] 爬取攻略: {}", url);
        return travelGuideCrawlerTool.crawlTravelGuide(url, userId != null ? userId : 1L);
    }
}
