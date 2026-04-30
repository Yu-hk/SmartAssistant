package com.example.smartassistant.tools;

import com.example.smartassistant.entity.TouristAttraction;
import com.example.smartassistant.mapper.TouristAttractionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 景点实时信息 MCP 工具
 *
 * <p>解决攻略信息不确定性的问题，提供实时数据支持：</p>
 * <ul>
 *     <li>实时开放状态和开放时间</li>
 *     <li>特殊活动/节日信息</li>
 *     <li>天气影响评估</li>
 *     <li>人流预测建议</li>
 * </ul>
 *
 * <p>数据来源：</p>
 * <ul>
 *     <li>高德地图 POI API（实时数据）</li>
 *     <li>本地景点数据库（基础信息）</li>
 *     <li>天气预报 API（天气影响）</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AttractionRealtimeTool {

    private final TouristAttractionMapper attractionMapper;

    @Value("${spring.amap.api.key}")
    private String amapApiKey;

    private static final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * 查询景点的实时信息
     *
     * <p>整合景点基础信息、实时开放状态、天气影响等多维度数据</p>
     *
     * @param attractionName 景点名称，如"西湖"、"故宫"、"泰山"
     * @param city 城市名称，如"杭州"、"北京"、"济南"
     * @param visitDate 计划游览日期，格式 yyyy-MM-dd，如 "2026-04-26"
     * @return 景点实时信息和建议
     */
    @Tool(description = "查询景点的实时信息，包括开放状态、门票、特殊活动、天气影响等，帮助规划出行")
    public String getAttractionRealtimeInfo(
            @ToolParam(description = "景点名称", required = true) String attractionName,
            @ToolParam(description = "城市名称", required = true) String city,
            @ToolParam(description = "计划游览日期，格式 yyyy-MM-dd，如 2026-04-26", required = false) String visitDate
    ) {
        log.info("[AttractionRealtime] 查询景点实时信息: name={}, city={}, date={}",
                attractionName, city, visitDate);

        StringBuilder result = new StringBuilder();
        result.append("【").append(attractionName).append("】实时信息\n");
        result.append("═══════════════════════════════════\n\n");

        // 1. 查询景点基础信息
        TouristAttraction attraction = queryLocalAttraction(attractionName, city);
        if (attraction != null) {
            result.append(formatAttractionBasicInfo(attraction));
        }

        // 2. 查询开放状态和特殊日期信息
        result.append(analyzeVisitDate(visitDate, city));

        // 3. 从高德获取实时POI信息
        String poiInfo = queryAmapPoi(attractionName, city);
        if (!poiInfo.isEmpty()) {
            result.append("📍 高德实时数据:\n").append(poiInfo).append("\n");
        }

        // 4. 天气影响评估
        if (visitDate != null && !visitDate.isEmpty()) {
            String weatherImpact = assessWeatherImpact(city, visitDate);
            result.append("🌤️ 出行建议:\n").append(weatherImpact).append("\n");
        }

        return result.toString();
    }

    /**
     * 查询多个景点的实时对比信息
     *
     * @param attractions 景点名称列表，逗号分隔，如 "西湖,灵隐寺,宋城"
     * @param city 城市名称
     * @param visitDate 计划游览日期
     * @return 多景点对比信息
     */
    @Tool(description = "对比查询多个景点的实时信息，帮助选择最佳目的地")
    public String compareAttractions(
            @ToolParam(description = "景点名称列表，逗号分隔", required = true) String attractions,
            @ToolParam(description = "城市名称", required = true) String city,
            @ToolParam(description = "计划游览日期，格式 yyyy-MM-dd", required = false) String visitDate
    ) {
        log.info("[AttractionRealtime] 对比景点: {}, city={}", attractions, city);

        StringBuilder result = new StringBuilder();
        result.append("【").append(city).append("景点对比】\n");
        result.append("═══════════════════════════════════\n\n");

        String[] names = attractions.split("[,，]");
        for (String s : names) {
            String name = s.trim();
            if (name.isEmpty()) continue;

            result.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            result.append(getAttractionRealtimeInfo(name, city, visitDate));
            result.append("\n");
        }

        return result.toString();
    }

    /**
     * 获取节假日/特殊活动信息
     *
     * @param attractionName 景点名称
     * @param city 城市名称
     * @param startDate 开始日期，格式 yyyy-MM-dd
     * @param endDate 结束日期，格式 yyyy-MM-dd
     * @return 时段内的特殊活动信息
     */
    @Tool(description = "查询景点在指定时间段内的节假日和特殊活动信息")
    public String getSpecialEvents(
            @ToolParam(description = "景点名称", required = true) String attractionName,
            @ToolParam(description = "城市名称", required = true) String city,
            @ToolParam(description = "开始日期，格式 yyyy-MM-dd", required = true) String startDate,
            @ToolParam(description = "结束日期，格式 yyyy-MM-dd", required = true) String endDate
    ) {
        log.info("[AttractionRealtime] 查询特殊活动: {}, {} 至 {}",
                attractionName, startDate, endDate);

        StringBuilder result = new StringBuilder();
        result.append("【").append(attractionName).append("】特殊活动\n");
        result.append("═══════════════════════════════════\n\n");

        // 解析日期
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);

        // 检查节假日
        List<String> holidays = checkHolidays(start, end);
        if (!holidays.isEmpty()) {
            result.append("📅 节假日提示:\n");
            holidays.forEach(h -> result.append("  • ").append(h).append("\n"));
            result.append("\n⚠️ 节假日期间通常人流量较大，建议提前预约门票\n\n");
        }

        // 常见节假日活动
        result.append("🎭 可能的节庆活动:\n");
        result.append(getPossibleSeasonalEvents(start, end));

        return result.toString();
    }

    // ========== 私有辅助方法 ==========

    /**
     * 查询本地景点数据库
     */
    private TouristAttraction queryLocalAttraction(String name, String city) {
        try {
            List<TouristAttraction> attractions = attractionMapper.selectList(null);
            return attractions.stream()
                    .filter(a -> a.getName().contains(name) || name.contains(a.getName()))
                    .filter(a -> a.getCity() == null || a.getCity().contains(city) || city.contains(a.getCity()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[AttractionRealtime] 本地景点查询失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 格式化景点基础信息
     */
    private String formatAttractionBasicInfo(TouristAttraction attraction) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 基础信息:\n");
        sb.append("  • 名称: ").append(attraction.getName()).append("\n");
        if (attraction.getLevel() != null) {
            sb.append("  • 等级: ").append(attraction.getLevel()).append("景区\n");
        }
        if (attraction.getTicketPrice() != null) {
            sb.append("  • 门票: ¥").append(attraction.getTicketPrice()).append("\n");
        }
        if (attraction.getOpenTime() != null) {
            sb.append("  • 开放时间: ").append(attraction.getOpenTime()).append("\n");
        }
        if (attraction.getSuggestDuration() != null) {
            sb.append("  • 建议游览时长: ").append(attraction.getSuggestDuration() / 60).append("小时\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * 分析访问日期的特殊性
     */
    private String analyzeVisitDate(String visitDate, String city) {
        if (visitDate == null || visitDate.isEmpty()) {
            return "📅 未指定日期，无法提供特定日期分析\n\n";
        }

        StringBuilder sb = new StringBuilder();
        LocalDate date = LocalDate.parse(visitDate);

        sb.append("📅 计划日期分析: ").append(visitDate).append("\n");

        // 星期几
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        String weekTip = switch (dayOfWeek) {
            case SATURDAY, SUNDAY -> "  • 周末，可能人流量较大\n";
            case FRIDAY -> "  • 周五下午开始人流量上升\n";
            default -> "  • 工作日，人流量相对较小\n";
        };
        sb.append(weekTip);

        // 距今天的天数
        long daysFromNow = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), date);
        if (daysFromNow <= 0) {
            sb.append("  • 今天，实时信息可能有效\n");
        } else if (daysFromNow <= 7) {
            sb.append("  • 距今").append(daysFromNow).append("天，天气预报可参考\n");
        } else if (daysFromNow <= 30) {
            sb.append("  • 距今").append(daysFromNow).append("天，请关注天气预报更新\n");
        } else {
            sb.append("  • 距今较长，请出行前再次确认开放状态\n");
        }

        sb.append("\n");
        return sb.toString();
    }

    /**
     * 查询高德地图 POI 信息
     */
    private String queryAmapPoi(String name, String city) {
        try {
            String keyword = URLEncoder.encode(name, StandardCharsets.UTF_8);
            String url = String.format(
                    "https://restapi.amap.com/v3/place/text?keywords=%s&city=%s&output=json&key=%s",
                    keyword, URLEncoder.encode(city, StandardCharsets.UTF_8), amapApiKey
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseAmapPoiResponse(response.body());
            }
        } catch (Exception e) {
            log.warn("[AttractionRealtime] 高德 POI 查询失败: {}", e.getMessage());
        }
        return "";
    }

    /**
     * 解析高德 POI 响应
     */
    private String parseAmapPoiResponse(String json) {
        // 简单解析 - 提取 POI 信息
        StringBuilder sb = new StringBuilder();

        // 检查是否成功
        if (!json.contains("\"info\":\"OK\"")) {
            return "";
        }

        // 提取第一个 POI 的相关信息
        int poiIndex = json.indexOf("\"location\":\"");
        if (poiIndex > 0) {
            sb.append("  • 位置信息已获取\n");
        }

        return sb.toString();
    }

    /**
     * 评估天气对出行的影响
     */
    private String assessWeatherImpact(String city, String visitDate) {
        try {
            // 调用天气查询
            String weatherUrl = String.format(
                    "https://restapi.amap.com/v3/weather/weatherInfo?city=%s&key=%s&extensions=all",
                    URLEncoder.encode(city, StandardCharsets.UTF_8), amapApiKey
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(weatherUrl))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return analyzeWeatherResponse(response.body(), visitDate);
            }
        } catch (Exception e) {
            log.warn("[AttractionRealtime] 天气查询失败: {}", e.getMessage());
        }
        return "  ⚠️ 天气数据暂不可用，请手动查询天气预报\n";
    }

    /**
     * 分析天气响应，生成出行建议
     */
    private String analyzeWeatherResponse(String json, String visitDate) {
        StringBuilder sb = new StringBuilder();

        // 提取天气信息
        String weather = extractJsonValue(json, "\"dayweather\":\"");
        String temperature = extractJsonValue(json, "\"daytemp\":\"");
        String wind = extractJsonValue(json, "\"daywind\":\"");

        sb.append("  • 天气: ").append(weather).append("\n");
        sb.append("  • 温度: ").append(temperature).append("°C\n");
        sb.append("  • 风力: ").append(wind).append("级\n");

        // 根据天气给出建议
        if (weather.contains("雨") || weather.contains("雪")) {
            sb.append("\n  💡 建议:\n");
            sb.append("    室内活动为主，注意防滑\n");
            sb.append("    建议携带雨具，穿着防滑鞋\n");
        } else if (weather.contains("晴")) {
            sb.append("\n  💡 建议:\n");
            sb.append("    适合户外活动，注意防晒\n");
            sb.append("    建议携带遮阳帽、墨镜、防晒霜\n");
        } else if (weather.contains("阴") || weather.contains("多云")) {
            sb.append("\n  💡 建议:\n");
            sb.append("    天气适宜，适合各类活动\n");
            sb.append("    户外室内皆可，灵活安排\n");
        }

        return sb.toString();
    }

    /**
     * 检查日期范围内的节假日
     */
    private List<String> checkHolidays(LocalDate start, LocalDate end) {
        List<String> holidays = new ArrayList<>();

        // 中国主要节假日检查（简化版）
        while (!start.isAfter(end)) {
            int month = start.getMonthValue();
            int day = start.getDayOfMonth();

            // 节假日判断（简化）
            if (month == 1 && day == 1) {
                holidays.add(start + " 元旦");
            } else if (month == 5 && day == 1) {
                holidays.add(start + " 劳动节");
            } else if (month == 10 && day <= 7) {
                holidays.add(start + " 国庆节");
            } else if (month == 4 && day >= 4 && day <= 6) {
                holidays.add(start + " 清明节");
            } else if (month == 6) {
                holidays.add(start + " 端午节");
            } else if (month == 9 && day >= 10) {
                holidays.add(start + " 中秋节");
            }

            // 周末提示（不需要特别标注到节假日列表中）
            start = start.plusDays(1);
        }

        return holidays;
    }

    /**
     * 根据季节获取可能的节庆活动
     */
    private String getPossibleSeasonalEvents(LocalDate start, LocalDate end) {
        StringBuilder sb = new StringBuilder();

        int month = start.getMonthValue();

        switch (month) {
            case 3, 4 -> {
                sb.append("  • 春季赏花活动（桃花、樱花、油菜花）\n");
                sb.append("  • 风筝节、踏青节\n");
            }
            case 5, 6 -> {
                sb.append("  • 采摘节（樱桃、草莓）\n");
                sb.append("  • 端午龙舟赛（南方水乡）\n");
            }
            case 7, 8 -> {
                sb.append("  • 音乐节、啤酒节\n");
                sb.append("  • 避暑纳凉活动\n");
            }
            case 9, 10 -> {
                sb.append("  • 中秋赏月活动\n");
                sb.append("  • 国庆黄金周特色活动\n");
                sb.append("  • 重阳登高节\n");
            }
            case 11, 12, 1, 2 -> {
                sb.append("  • 温泉节、滑雪节\n");
                sb.append("  • 春节庙会、灯会\n");
                sb.append("  • 圣诞/元旦活动\n");
            }
        }

        return sb.toString();
    }

    /**
     * 从 JSON 字符串中提取指定键的值
     */
    private String extractJsonValue(String json, String key) {
        int index = json.indexOf(key);
        if (index == -1) return "未知";

        int start = index + key.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return "未知";

        return json.substring(start, end);
    }
}
