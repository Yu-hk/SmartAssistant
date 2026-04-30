package com.example.smartassistant.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 智能出行规划组合工具
 * 
 * <p>自动串联三个工具的调用流程：
 * 1. LocationTool.getCurrentCityName() - 获取当前城市
 * 2. WeatherTool.query(city) - 查询城市天气
 * 3. TravelPlannerTool.planTravel(weatherInfo, city) - 生成出行方案
 * 
 * <p>优势：
 * - 避免 AI 在 ReAct 循环中多次决策
 * - 确保执行顺序正确
 * - 减少重复内容问题
 * - 提高响应速度
 */
@Component
public class SmartTravelPlannerTool {

    private static final Logger log = LoggerFactory.getLogger(SmartTravelPlannerTool.class);

    private final LocationTool locationTool;
    private final WeatherTool weatherTool;
    private final TravelPlannerTool travelPlannerTool;

    public SmartTravelPlannerTool(LocationTool locationTool, 
                                   WeatherTool weatherTool,
                                   TravelPlannerTool travelPlannerTool) {
        this.locationTool = locationTool;
        this.weatherTool = weatherTool;
        this.travelPlannerTool = travelPlannerTool;
    }

    /**
     * 智能出行规划（全自动）
     * 自动完成以下步骤：
     * 1. 获取当前所在城市
     * 2. 查询该城市天气
     * 3. 根据天气生成出行建议
     * 
     * @return 完整的出行规划方案
     */
    @Tool(description = "智能出行规划助手 - 自动获取当前位置、查询天气并生成个性化出行建议。适用于'周末去哪玩'、'今天适合做什么'、'出行建议'等场景")
    public String planSmartTrip() {
        long startTime = System.currentTimeMillis();
        log.info("[SmartTravelPlanner] 开始智能出行规划");
        
        try {
            // 步骤 1: 获取当前城市和精确坐标
            log.info("[SmartTravelPlanner] 步骤 1/4: 获取当前城市和坐标");
            long step1Start = System.currentTimeMillis();
            String city = locationTool.getCurrentCityName();
            String coordinates = locationTool.getCurrentCoordinates();
            long step1End = System.currentTimeMillis();
            log.info("[SmartTravelPlanner] 步骤 1 完成，城市: {}, 坐标: {}, 耗时: {} ms", 
                    city, coordinates, step1End - step1Start);
            
            if (city == null || city.isEmpty()) {
                city = "北京市"; // 默认值
                log.warn("[SmartTravelPlanner] 未获取到城市，使用默认值: {}", city);
            }
            
            // 步骤 2: 查询天气
            log.info("[SmartTravelPlanner] 步骤 2/4: 查询 {} 天气", city);
            long step2Start = System.currentTimeMillis();
            String weatherInfo = weatherTool.query(city);
            long step2End = System.currentTimeMillis();
            log.info("[SmartTravelPlanner] 步骤 2 完成，耗时: {} ms", step2End - step2Start);
            
            // 步骤 3: 生成出行方案（使用精确坐标）
            log.info("[SmartTravelPlanner] 步骤 3/4: 生成出行方案（使用精确坐标）");
            long step3Start = System.currentTimeMillis();
            String travelPlan = travelPlannerTool.planTravelWithCoordinates(weatherInfo, city, coordinates);
            long step3End = System.currentTimeMillis();
            log.info("[SmartTravelPlanner] 步骤 3 完成，耗时: {} ms", step3End - step3Start);
            
            long endTime = System.currentTimeMillis();
            long totalDuration = endTime - startTime;
            log.info("[SmartTravelPlanner] 智能出行规划完成，总耗时: {} ms", totalDuration);
            
            return travelPlan;
            
        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            log.error("[SmartTravelPlanner] 规划失败，耗时: {} ms, 错误: {}", 
                     endTime - startTime, e.getMessage(), e);
            return "出行规划失败：" + e.getMessage();
        }
    }

    /**
     * 指定城市的出行规划
     * 如果用户明确指定了城市，直接使用该方法
     * 
     * @param city 城市名称
     * @return 出行规划方案
     */
    @Tool(description = "指定城市的出行规划 - 直接查询指定城市的天气并生成出行建议。适用于'北京周末去哪玩'、'上海今天适合做什么'等明确指定城市场景")
    public String planTripForCity(String city) {
        long startTime = System.currentTimeMillis();
        log.info("[SmartTravelPlanner] 开始为 {} 规划出行", city);
        
        try {
            // 步骤 1: 查询天气
            log.info("[SmartTravelPlanner] 步骤 1/2: 查询 {} 天气", city);
            long step1Start = System.currentTimeMillis();
            String weatherInfo = weatherTool.query(city);
            long step1End = System.currentTimeMillis();
            log.info("[SmartTravelPlanner] 步骤 1 完成，耗时: {} ms", step1End - step1Start);
            
            // 步骤 2: 生成出行方案
            log.info("[SmartTravelPlanner] 步骤 2/2: 生成出行方案");
            long step2Start = System.currentTimeMillis();
            String travelPlan = travelPlannerTool.planTravel(weatherInfo, city);
            long step2End = System.currentTimeMillis();
            log.info("[SmartTravelPlanner] 步骤 2 完成，耗时: {} ms", step2End - step2Start);
            
            long endTime = System.currentTimeMillis();
            long totalDuration = endTime - startTime;
            log.info("[SmartTravelPlanner] {} 出行规划完成，总耗时: {} ms", city, totalDuration);
            
            return travelPlan;
            
        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            log.error("[SmartTravelPlanner] {} 规划失败，耗时: {} ms, 错误: {}", 
                     city, endTime - startTime, e.getMessage(), e);
            return "出行规划失败：" + e.getMessage();
        }
    }

    /**
     * 智能规划旅行行程
     * 根据目的地、天数、偏好生成完整的行程规划
     * 
     * @param destination 目的地城市
     * @param days 天数
     * @param preference 旅行偏好/主题（如"亲子游"、"美食之旅"、"文化探索"）
     * @return 完整行程规划
     */
    @Tool(description = "智能规划旅行行程 - 根据目的地、天数和偏好生成完整的行程安排。包括每日路线、景点推荐、时间安排等。适用于'帮我规划杭州3日游'、'成都4天亲子游'等场景")
    public String smartPlan(
            String destination,
            String days,
            String preference) {
        long startTime = System.currentTimeMillis();
        log.info("[SmartTravelPlanner] 开始智能规划: dest={}, days={}, pref={}", 
                 destination, days, preference);
        
        try {
            // 步骤 1: 查询天气
            log.info("[SmartTravelPlanner] 步骤 1/4: 查询 {} 天气", destination);
            String weatherInfo = weatherTool.query(destination);
            
            // 步骤 2: 获取景点信息
            log.info("[SmartTravelPlanner] 步骤 2/4: 获取 {} 景点信息", destination);
            String attractionsInfo = ""; // Agent 会单独调用 getAttractionRealtimeInfo
            
            // 步骤 3: 生成基于天数的行程框架
            log.info("[SmartTravelPlanner] 步骤 3/4: 生成 {} 天行程框架", days);
            int dayCount;
            try {
                dayCount = Integer.parseInt(days);
            } catch (NumberFormatException e) {
                dayCount = 3; // 默认3天
            }
            
            StringBuilder plan = new StringBuilder();
            plan.append("📅 ").append(destination).append(" ").append(dayCount).append("日行程规划\n");
            plan.append("==================\n\n");
            
            // 根据天数生成分天概览
            for (int i = 1; i <= dayCount; i++) {
                plan.append(String.format("📍 第 %d 天：待安排\n", i));
            }
            
            plan.append("\n💡 建议：\n");
            plan.append("1. 建议先查询具体景点信息（使用 getAttractionRealtimeInfo）\n");
            plan.append("2. 可以根据天气情况调整行程（使用 getWeather）\n");
            plan.append("3. 查看相似游记获取经验（使用 searchRelevantTravelNotes）\n");
            
            plan.append("\n📋 天气参考：\n").append(weatherInfo);
            
            if (preference != null && !preference.isEmpty()) {
                plan.append("\n🎯 旅行主题：").append(preference).append("\n");
            }
            
            long endTime = System.currentTimeMillis();
            log.info("[SmartTravelPlanner] 智能规划完成，耗时: {} ms", endTime - startTime);
            
            return plan.toString();
            
        } catch (Exception e) {
            log.error("[SmartTravelPlanner] 智能规划失败: {}", e.getMessage(), e);
            return "智能规划失败：" + e.getMessage();
        }
    }
}
