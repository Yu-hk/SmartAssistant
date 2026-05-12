package com.example.smartassistant.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 智能旅行规划工具
 * <p>
 * 核心工具：{@link #smartPlan(String, String, String)} — 根据目的地、天数、偏好
 * 串联 WeatherTool + Amap POI 实时数据生成完整行程。
 * <p>
 * 已移除 planSmartTrip / planTripForCity（依赖 TravelPlannerTool 的硬编码数据），
 * LLM 的 ReAct 循环可自行编排 LocationTool → WeatherTool → AttractionRealtimeTool。
 */
@Component
public class SmartTravelPlannerTool {

    private static final Logger log = LoggerFactory.getLogger(SmartTravelPlannerTool.class);

    private final WeatherTool weatherTool;
    private final AttractionRealtimeTool attractionTool;

    public SmartTravelPlannerTool(WeatherTool weatherTool,
                                   AttractionRealtimeTool attractionTool) {
        this.weatherTool = weatherTool;
        this.attractionTool = attractionTool;
    }

    /**
     * 智能规划旅行行程 — 核心工具
     * <p>
     * 根据目的地、天数、偏好，串联 WeatherTool + Amap POI 实时数据生成完整行程框架。
     * LLM 收到返回后可继续调用 searchRelevantTravelNotes、getAttractionRealtimeInfo 等补充细节。
     *
     * @param destination 目的地城市
     * @param days 天数
     * @param preference 旅行偏好/主题（如"亲子游"、"美食之旅"、"文化探索"）
     * @return 完整行程规划（含天气、景点概览、每日骨架）
     */
    @Tool(description = "智能规划旅行行程 - 根据目的地、天数和偏好生成完整的行程安排。包括当地天气、推荐景点、每日路线骨架。适用于'帮我规划杭州3日游'、'成都4天亲子游'等场景。注意：此工具会自动调用天气和景点查询，无需额外调用")
    public String smartPlan(
            String destination,
            String days,
            String preference) {
        long startTime = System.currentTimeMillis();
        log.info("[SmartTravelPlanner] 开始智能规划: dest={}, days={}, pref={}", 
                 destination, days, preference);

        try {
            int dayCount;
            try {
                dayCount = Integer.parseInt(days);
            } catch (NumberFormatException e) {
                dayCount = 3;
            }
            if (dayCount < 1) dayCount = 1;
            if (dayCount > 14) dayCount = 14;

            // 步骤 1: 查询天气
            log.info("[SmartTravelPlanner] 步骤 1/3: 查询 {} 天气", destination);
            String weatherInfo = weatherTool.query(destination);

            // 步骤 2: 获取景点信息（通过 Amap POI 实时查询）
            log.info("[SmartTravelPlanner] 步骤 2/3: 获取 {} 景点信息", destination);
            String attractionsInfo = "";
            try {
                attractionsInfo = attractionTool.getAttractionRealtimeInfo("热门景点", destination, "");
            } catch (Exception e) {
                log.warn("[SmartTravelPlanner] 景点查询失败（非致命）: {}", e.getMessage());
                attractionsInfo = "（景点信息暂未获取，建议后续调用 getAttractionRealtimeInfo 查询）";
            }

            // 步骤 3: 组装结果
            log.info("[SmartTravelPlanner] 步骤 3/3: 组装行程规划");
            StringBuilder plan = new StringBuilder();
            plan.append("📅 ").append(destination).append(" ").append(dayCount).append("日行程规划");
            if (preference != null && !preference.isEmpty()) {
                plan.append("（").append(preference).append("）");
            }
            plan.append("\n").append("=".repeat(30)).append("\n\n");

            plan.append("🌤 天气概况\n");
            plan.append(weatherInfo).append("\n\n");

            plan.append("🏛 推荐景点\n");
            plan.append(attractionsInfo).append("\n\n");

            plan.append("📋 行程框架\n");
            for (int i = 1; i <= dayCount; i++) {
                plan.append("第 ").append(i).append(" 天：\n");
                plan.append("  ├ 上午：\n");
                plan.append("  ├ 下午：\n");
                plan.append("  └ 晚上：\n");
                if (i < dayCount) plan.append("\n");
            }

            plan.append("\n💡 小贴士\n");
            plan.append("• 可使用 getAttractionRealtimeInfo 查询具体景点详情\n");
            plan.append("• 可使用 searchRelevantTravelNotes 查看其他游客的游记\n");
            plan.append("• 可根据天气情况灵活调整每日行程\n");

            long endTime = System.currentTimeMillis();
            log.info("[SmartTravelPlanner] 智能规划完成，耗时: {} ms", endTime - startTime);

            return plan.toString();

        } catch (Exception e) {
            log.error("[SmartTravelPlanner] 智能规划失败: {}", e.getMessage(), e);
            return "智能规划失败：" + e.getMessage();
        }
    }
}
