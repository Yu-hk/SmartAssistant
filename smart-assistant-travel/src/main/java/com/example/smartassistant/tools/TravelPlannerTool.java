package com.example.smartassistant.tools;

import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * 出行规划工具
 * 
 * <p>根据天气情况和温度，智能推荐合适的娱乐项目和出行建议</p>
 * 
 * <p>功能特点：</p>
 * <ul>
 *     <li>晴朗天气：推荐户外活动，并根据温度提示衣物建议</li>
 *     <li>降雨/降雪天气：推荐室内活动</li>
 *     <li>多云/阴天：推荐半户外或灵活安排的活动</li>
 * </ul>
 * 
 * <p>⭐ 使用中文分词器增强活动匹配能力</p>
 */
@Component
public class TravelPlannerTool {

    private static final Logger log = LoggerFactory.getLogger(TravelPlannerTool.class);

    /** ⭐ 中文分词器 */
    private final ChineseTokenizer tokenizer;

    @Value("${amap.api.key:}")
    private String amapApiKey;

    public TravelPlannerTool(ChineseTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    // 室外娱乐项目库
    private static final List<String> OUTDOOR_ACTIVITIES = Arrays.asList(
        "公园散步/野餐",
        "骑自行车",
        "登山徒步",
        "户外摄影",
        "放风筝",
        "滑板/轮滑",
        "户外篮球/足球",
        "河边/湖边垂钓",
        "植物园/动物园游览",
        "露天音乐会/市集"
    );

    // 室内娱乐项目库
    private static final List<String> INDOOR_ACTIVITIES = Arrays.asList(
        "看电影/电视剧",
        "做手工/DIY 制作",
        "烘焙/烹饪",
        "阅读书籍",
        "桌游/棋牌游戏",
        "室内健身/瑜伽",
        "绘画/书法练习",
        "音乐练习/唱歌",
        "参观博物馆/美术馆",
        "室内游乐场/KTV",
        "购物逛街",
        "学习新技能（在线课程）"
    );

    // 灵活活动库（室内外皆可）
    private static final List<String> FLEXIBLE_ACTIVITIES = Arrays.asList(
        "咖啡馆休闲",
        "图书馆学习",
        "商场购物",
        "电影院观影",
        "室内攀岩馆",
        "游泳馆游泳",
        "保龄球馆",
        "密室逃脱"
    );

    /**
     * 根据天气情况规划出行安排
     * 
     * @param weatherInfo 天气信息，包含天气状况和温度
     * @param city 城市名称
     * @return 出行建议和娱乐项目推荐
     */
    @Tool(description = "根据天气情况和城市信息，智能推荐合适的娱乐项目和出行建议，包括衣物提示和活动安排")
    public String planTravel(String weatherInfo, String city) {
        return planTravelWithCoordinates(weatherInfo, city, null);
    }

    /**
     * 根据天气情况、城市信息和用户坐标，智能推荐出行方案
     *
     * @param weatherInfo 天气信息
     * @param city 城市名称
     * @param coordinates 用户精确坐标，格式：“纬度,经度”，为 null 时使用城市中心坐标
     * @return 出行建议
     */
    public String planTravelWithCoordinates(String weatherInfo, String city, String coordinates) {
        long startTime = System.currentTimeMillis();
        log.info("[TravelPlanner] 开始规划，城市: {}, 坐标: {}", city, 
                coordinates != null ? coordinates : "城市中心");
            
        // 解析天气信息
        WeatherCondition condition = parseWeatherCondition(weatherInfo);
            
        StringBuilder recommendation = new StringBuilder();
        recommendation.append("【").append(city).append("】出行建议\n");
            
        // 添加天气和衣物建议
        appendWeatherAndClothing(recommendation, condition);
            
        // 根据天气类型选择活动，并搜索具体场所
        List<String> recommendedActivities = getActivitiesByWeather(condition);
            
        // 获取用户坐标
        double latitude, longitude;
        if (coordinates != null && !coordinates.isEmpty()) {
            String[] coords = coordinates.split(",");
            if (coords.length == 2) {
                latitude = Double.parseDouble(coords[0].trim());
                longitude = Double.parseDouble(coords[1].trim());
            } else {
                String[] cityCoords = getCityCenterCoordinates(city);
                latitude = Double.parseDouble(cityCoords[0]);
                longitude = Double.parseDouble(cityCoords[1]);
            }
        } else {
            String[] cityCoords = getCityCenterCoordinates(city);
            latitude = Double.parseDouble(cityCoords[0]);
            longitude = Double.parseDouble(cityCoords[1]);
        }
            
        // 为每个活动类型搜索具体场所并生成推荐
        appendActivityRecommendations(recommendation, recommendedActivities, city, latitude, longitude);
            
        long endTime = System.currentTimeMillis();
        log.info("[TravelPlanner] 总耗时: {} ms", endTime - startTime);
            
        return recommendation.toString();
    }

    /**
     * 生成晴朗天气的推荐
     */
    private String generateSunnyRecommendations(WeatherCondition condition, String city) {
        StringBuilder rec = new StringBuilder();
        
        // 天气描述
        rec.append("天气：晴，温度：").append(condition.temperature).append("°C\n");
        
        // 衣物建议
        rec.append("衣物：").append(getClothingAdvice(condition.temperature)).append("\n");
        
        // 推荐户外活动
        rec.append("推荐活动：");
        List<String> activities = selectRandomActivities(OUTDOOR_ACTIVITIES);
        for (int i = 0; i < activities.size(); i++) {
            if (i > 0) rec.append(", ");
            rec.append((i + 1)).append(".").append(activities.get(i));
        }
        
        return rec.toString();
    }

    /**
     * 添加天气和衣物建议
     */
    private void appendWeatherAndClothing(StringBuilder rec, WeatherCondition condition) {
        String weatherDesc;
        String weatherEmoji = switch (condition.type) {
            case SUNNY -> {
                weatherDesc = "晴";
                yield "☀️";
            }
            case RAINY -> {
                weatherDesc = "雨";
                yield "🌧️";
            }
            case SNOWY -> {
                weatherDesc = "雪";
                yield "❄️";
            }
            case CLOUDY, OVERCAST -> {
                weatherDesc = condition.description != null ? condition.description : "多云";
                yield "⛅";
            }
            default -> {
                weatherDesc = condition.description != null ? condition.description : "未知";
                yield "🌤️";
            }
        };

        rec.append("\ud83d\udd50 天气：").append(weatherEmoji).append(" ").append(weatherDesc)
           .append(", 温度：").append(condition.temperature).append("°C\n");
        rec.append("\ud83d\udc55 衣物：").append(getClothingAdvice(condition.temperature));
        
        if (condition.type == WeatherType.RAINY) {
            rec.append(" ☔");
        } else if (condition.type == WeatherType.SNOWY) {
            rec.append(" \ud83e\udde4");
        }
        rec.append("\n");
    }

    /**
     * 根据天气类型获取活动列表
     */
    private List<String> getActivitiesByWeather(WeatherCondition condition) {
        return switch (condition.type) {
            case SUNNY -> OUTDOOR_ACTIVITIES;
            case RAINY, SNOWY -> INDOOR_ACTIVITIES;
            case CLOUDY, OVERCAST -> FLEXIBLE_ACTIVITIES;
            default -> {
                List<String> all = new ArrayList<>();
                all.addAll(OUTDOOR_ACTIVITIES);
                all.addAll(INDOOR_ACTIVITIES);
                yield all;
            }
        };
    }

    /**
     * 为每个活动搜索具体场所并生成推荐
     */
    private void appendActivityRecommendations(StringBuilder rec, List<String> activities, 
                                               String city, double latitude, double longitude) {
        // 随机选择 3 个活动
        List<String> selectedActivities = selectRandomActivities(activities);
        
        rec.append("\n\ud83c\udfaf 推荐活动：\n");
        
        for (int i = 0; i < selectedActivities.size(); i++) {
            String activity = selectedActivities.get(i);
            
            // 获取该活动对应的搜索关键词
            List<String> keywords = mapActivityToKeywords(activity);
            
            // 搜索附近的场所（只取第一个关键词）
            String keyword = keywords.isEmpty() ? "休闲" : keywords.get(0);
            List<String> pois = searchAmapPOIWithDistance(latitude, longitude, keyword);
            
            // 生成推荐项
            String emoji = getActivityEmoji(activity);
            rec.append((i + 1)).append(". ").append(emoji).append(" ").append(activity);
            
            if (!pois.isEmpty()) {
                // 取第一个场所
                String poi = pois.get(0);
                rec.append(" → ").append(poi);
            }
            
            rec.append("\n");
        }
    }

    /**
     * 根据活动类型获取对应的表情符号
     * <p>
     * ⭐ 使用中文分词器增强活动匹配能力
     */
    private String getActivityEmoji(String activity) {
        // ⭐ 使用分词器增强关键词匹配
        if (tokenizer.containsAnyKeyword(activity, Set.of("图书馆", "阅读"))) {
            return "\ud83d\udcda";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("咖啡", "休闲"))) {
            return "☕";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("购物", "商场"))) {
            return "\ud83d\udecd️";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("电影", "观影"))) {
            return "\ud83c\udfac";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("健身", "瑜伽"))) {
            return "\ud83c\udfcb️";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("游泳"))) {
            return "\ud83c\udfca";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("保龄球"))) {
            return "\ud83c\udfb3";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("攀岩"))) {
            return "\ud83e\uddd7";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("密室", "逃脱"))) {
            return "\ud83d\udd10";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("KTV", "唱歌"))) {
            return "\ud83c\udfa4";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("公园", "骑行", "摄影"))) {
            return "\ud83c\udfde️";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("博物馆", "美术馆", "绘画"))) {
            return "\ud83c\udfdb️";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("烹饪", "烘焙"))) {
            return "\ud83c\udf73";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("SPA", "美容"))) {
            return "💆";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("登山", "徒步"))) {
            return "⛰️";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("风筝"))) {
            return "\ud83e\ude81";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("滑板", "轮滑"))) {
            return "\ud83d\udef9";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("篮球", "足球"))) {
            return "⚽";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("垂钓", "钓鱼"))) {
            return "\ud83c\udfa3";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("动物园", "植物园"))) {
            return "\ud83e\udd93";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("手工", "DIY"))) {
            return "✂️";
        } else if (tokenizer.containsAnyKeyword(activity, Set.of("桌游", "棋牌"))) {
            return "\ud83c\udfb2";
        } else {
            return "✨";
        }
    }

    /**
     * 搜索周边 POI 并计算距离
     */
    private List<String> searchAmapPOIWithDistance(double latitude, double longitude, String keyword) {
        List<String> results = new ArrayList<>();
        
        if (amapApiKey == null || amapApiKey.isEmpty()) {
            return results;
        }
        
        try {
            String url = String.format(
                "https://restapi.amap.com/v3/place/around?location=%.6f,%.6f&keywords=%s&radius=%d&key=%s&offset=3",
                longitude, latitude,  // 高德 API 要求经度在前
                URLEncoder.encode(keyword, StandardCharsets.UTF_8),
                5000,
                amapApiKey
            );
            
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .build();
            
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                String body = response.body();
                parsePoiResultsWithDistance(body, latitude, longitude, results);
            }
        } catch (Exception e) {
            log.warn("[高德API] 搜索 {} 失败: {}", keyword, e.getMessage());
        }
        
        return results;
    }

    /**
     * 解析 POI 结果并计算距离
     */
    private void parsePoiResultsWithDistance(String jsonResponse, double userLat, double userLon, List<String> results) {
        int poisIndex = jsonResponse.indexOf("\"pois\":");
        if (poisIndex == -1) return;
        
        int startIndex = poisIndex + "\"pois\":".length() + 1;
        int endIndex = jsonResponse.lastIndexOf("]");
        if (endIndex <= startIndex) return;
        
        String poisContent = jsonResponse.substring(startIndex, endIndex);
        String[] pois = poisContent.split("\\{\"");
        
        for (String poi : pois) {
            if (poi.trim().isEmpty()) continue;
            
            String name = extractJsonField(poi, "name");
            String address = extractJsonField(poi, "address");
            String location = extractJsonField(poi, "location");
            
            if (name != null && !name.isEmpty()) {
                StringBuilder item = new StringBuilder(name);
                
                if (address != null && !address.isEmpty()) {
                    item.append("（").append(address);
                    
                    // 计算距离
                    if (location != null && location.contains(",")) {
                        String[] coords = location.split(",");
                        if (coords.length == 2) {
                            try {
                                double poiLon = Double.parseDouble(coords[0]);
                                double poiLat = Double.parseDouble(coords[1]);
                                double distance = calculateDistance(userLat, userLon, poiLat, poiLon);
                                
                                if (distance < 1000) {
                                    item.append("，距离 ").append(String.format("%.0f", distance)).append("m");
                                } else {
                                    item.append("，距离 ").append(String.format("%.1f", distance / 1000)).append("km");
                                }
                            } catch (NumberFormatException e) {
                                // 忽略距离计算错误
                            }
                        }
                    }
                    
                    item.append("）");
                }
                
                results.add(item.toString());
            }
        }
    }

    /**
     * 计算两点之间的距离（Haversine 公式）
     */
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371000; // 地球半径（米）
        
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        
        return R * c;
    }

    /**
     * 将活动类型映射为搜索关键词
     */
    private List<String> mapActivityToKeywords(String activity) {
        List<String> keywords = new ArrayList<>();
        
        // 图书馆/阅读类
        if (activity.contains("图书馆") || activity.contains("阅读")) {
            keywords.add("图书馆");
        }
        // 咖啡/休闲类
        else if (activity.contains("咖啡") || activity.contains("休闲")) {
            keywords.add("咖啡厅");
        }
        // 购物类
        else if (activity.contains("购物") || activity.contains("商场")) {
            keywords.add("购物中心");
        }
        // 电影类
        else if (activity.contains("电影") || activity.contains("观影")) {
            keywords.add("电影院");
        }
        // 健身/瑜伽类
        else if (activity.contains("健身") || activity.contains("瑜伽")) {
            keywords.add("健身房");
        }
        // 游泳类
        else if (activity.contains("游泳")) {
            keywords.add("游泳馆");
        }
        // 保龄球类
        else if (activity.contains("保龄球")) {
            keywords.add("保龄球馆");
        }
        // 攀岩类
        else if (activity.contains("攀岩")) {
            keywords.add("攀岩馆");
        }
        // 密室逃脱类
        else if (activity.contains("密室") || activity.contains("逃脱")) {
            keywords.add("密室逃脱");
        }
        // KTV/唱歌类
        else if (activity.contains("KTV") || activity.contains("唱歌")) {
            keywords.add("KTV");
        }
        // 公园/骑行/摄影类
        else if (activity.contains("公园") || activity.contains("骑行") || activity.contains("摄影")) {
            keywords.add("公园");
        }
        // 博物馆/美术馆/绘画类
        else if (activity.contains("博物馆") || activity.contains("美术馆") || activity.contains("绘画")) {
            keywords.add("博物馆");
        }
        // 烹饪/烘焙类
        else if (activity.contains("烹饪") || activity.contains("烘焙")) {
            keywords.add("美食");
        }
        // SPA/美容类
        else if (activity.contains("SPA") || activity.contains("美容")) {
            keywords.add("美容 SPA");
        }
        // 登山/徒步类
        else if (activity.contains("登山") || activity.contains("徒步")) {
            keywords.add("登山");
        }
        // 放风筝类
        else if (activity.contains("风筝")) {
            keywords.add("公园");
        }
        // 滑板/轮滑类
        else if (activity.contains("滑板") || activity.contains("轮滑")) {
            keywords.add("运动");
        }
        // 篮球/足球类
        else if (activity.contains("篮球") || activity.contains("足球")) {
            keywords.add("篮球场");
        }
        // 垂钓类
        else if (activity.contains("垂钓") || activity.contains("钓鱼")) {
            keywords.add("钓鱼");
        }
        // 动物园/植物园类
        else if (activity.contains("动物园") || activity.contains("植物园")) {
            keywords.add("动物园");
        }
        // 音乐会/市集类
        else if (activity.contains("音乐会") || activity.contains("市集")) {
            keywords.add("市集");
        }
        // 手工/DIY 类
        else if (activity.contains("手工") || activity.contains("DIY")) {
            keywords.add("手工");
        }
        // 桌游/棋牌类
        else if (activity.contains("桌游") || activity.contains("棋牌")) {
            keywords.add("桌游");
        }
        // 学习/培训类
        else if (activity.contains("学习") || activity.contains("课程") || activity.contains("培训")) {
            keywords.add("培训机构");
        }
        // 默认：休闲娱乐
        else {
            keywords.add("休闲娱乐");
        }
        
        return keywords;
    }

    /**
     * 生成恶劣天气（雨/雪）的推荐
     */
    private String generateBadWeatherRecommendations(WeatherCondition condition) {
        StringBuilder rec = new StringBuilder();
        
        // 天气描述
        String weatherDesc = condition.type == WeatherType.RAINY ? "雨" : "雪";
        rec.append("天气：").append(weatherDesc).append(", 温度：").append(condition.temperature).append("°C\n");
        
        // 衣物建议
        rec.append("衣物：").append(getClothingAdvice(condition.temperature));
        if (condition.type == WeatherType.RAINY) {
            rec.append("，出门请带伞\n");
        } else {
            rec.append("，注意保暖防滑\n");
        }
        
        // 推荐室内活动
        rec.append("推荐活动：");
        List<String> activities = selectRandomActivities(INDOOR_ACTIVITIES);
        for (int i = 0; i < activities.size(); i++) {
            if (i > 0) rec.append(", ");
            rec.append((i + 1)).append(".").append(activities.get(i));
        }
        
        return rec.toString();
    }

    /**
     * 生成多云/阴天的推荐
     */
    private String generateCloudyRecommendations(WeatherCondition condition, String city) {
        StringBuilder rec = new StringBuilder();
        
        // 天气描述
        rec.append("天气：").append(condition.description).append(", 温度：").append(condition.temperature).append("°C\n");
        
        // 衣物建议
        rec.append("衣物：").append(getClothingAdvice(condition.temperature)).append("\n");
        
        // 推荐灵活活动
        rec.append("推荐活动：");
        List<String> activities = selectRandomActivities(FLEXIBLE_ACTIVITIES);
        for (int i = 0; i < activities.size(); i++) {
            if (i > 0) rec.append(", ");
            rec.append((i + 1)).append(".").append(activities.get(i));
        }
        
        return rec.toString();
    }

    /**
     * 生成默认推荐
     */
    private String generateDefaultRecommendations(WeatherCondition condition) {
        StringBuilder rec = new StringBuilder();
        
        rec.append("天气：").append(condition.description != null ? condition.description : "未知").append(", 温度：").append(condition.temperature).append("°C\n");
        
        rec.append("衣物：").append(getClothingAdvice(condition.temperature)).append("\n");
        
        rec.append("推荐活动：");
        List<String> allActivities = new ArrayList<>();
        allActivities.addAll(OUTDOOR_ACTIVITIES.subList(0, 5));
        allActivities.addAll(INDOOR_ACTIVITIES.subList(0, 5));
        List<String> activities = selectRandomActivities(allActivities);
        for (int i = 0; i < activities.size(); i++) {
            if (i > 0) rec.append(", ");
            rec.append((i + 1)).append(".").append(activities.get(i));
        }
        
        return rec.toString();
    }

    /**
     * 获取城市附近的娱乐项目推荐
     * 根据推荐的活动类型智能匹配附近的场所
     */
    private String getNearbyEntertainmentRecommendations(String city, List<String> recommendedActivities) {
        return getNearbyEntertainmentRecommendationsWithCoordinates(city, recommendedActivities, null);
    }

    /**
     * 获取城市附近的娱乐项目推荐（支持精确坐标）
     * 
     * @param city 城市名称
     * @param recommendedActivities 推荐的活动类型列表
     * @param coordinates 用户精确坐标，格式："纬度,经度"，为 null 时使用城市中心坐标
     * @return 娱乐场所推荐信息
     */
    public String getNearbyEntertainmentRecommendationsWithCoordinates(
            String city, List<String> recommendedActivities, String coordinates) {
        
        long startTime = System.currentTimeMillis();
        log.info("[TravelPlanner] 开始查询附近娱乐，城市: {}, 坐标: {}", 
                city, coordinates != null ? coordinates : "城市中心");
        
        try {
            double latitude, longitude;
            
            if (coordinates != null && !coordinates.isEmpty()) {
                // 使用用户精确坐标
                String[] coords = coordinates.split(",");
                if (coords.length == 2) {
                    latitude = Double.parseDouble(coords[0].trim());
                    longitude = Double.parseDouble(coords[1].trim());
                    
                    // 计算与城市中心的距离
                    String[] cityCoords = getCityCenterCoordinates(city);
                    double cityLat = Double.parseDouble(cityCoords[0]);
                    double cityLon = Double.parseDouble(cityCoords[1]);
                    double latDiff = Math.abs(latitude - cityLat);
                    double lonDiff = Math.abs(longitude - cityLon);
                    double distanceKm = Math.sqrt(latDiff * latDiff + lonDiff * lonDiff) * 111;
                    
                    log.info("[TravelPlanner] 使用用户精确坐标: {}, {}", latitude, longitude);
                    log.info("[TravelPlanner] 城市中心坐标: {}, {}", cityLat, cityLon);
                    log.info("[TravelPlanner] 坐标偏移: 纬度 {}, 经度 {}, 距离约 {} 公里",
                            latDiff, lonDiff, distanceKm);
                } else {
                    // 坐标格式错误，使用城市中心
                    String[] cityCoords = getCityCenterCoordinates(city);
                    latitude = Double.parseDouble(cityCoords[0]);
                    longitude = Double.parseDouble(cityCoords[1]);
                    log.warn("[TravelPlanner] 坐标格式错误，使用城市中心坐标");
                }
            } else {
                // 使用城市中心坐标
                String[] cityCoords = getCityCenterCoordinates(city);
                latitude = Double.parseDouble(cityCoords[0]);
                longitude = Double.parseDouble(cityCoords[1]);
                log.info("[TravelPlanner] 使用城市中心坐标: {}, {}", latitude, longitude);
            }
            
            // 根据推荐的活动类型，智能选择搜索关键词
            List<String> searchKeywords = determineSearchKeywords(recommendedActivities);
            
            StringBuilder result = new StringBuilder();
            boolean hasResults = false;
            
            // 遍历搜索关键词，获取对应的场所
            for (String keyword : searchKeywords) {
                List<String> places = searchAmapPOI(latitude, longitude, keyword);
                if (!places.isEmpty()) {
                    result.append(getCategoryTitle(keyword)).append("\n");
                    for (int i = 0; i < Math.min(2, places.size()); i++) {
                        result.append("• ").append(places.get(i)).append("\n");
                    }
                    hasResults = true;
                }
            }
            
            long endTime = System.currentTimeMillis();
            log.info("[TravelPlanner] 附近娱乐查询完成，耗时: {} ms", endTime - startTime);
            
            return hasResults ? result.toString().trim() : "";
            
        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            log.error("[TravelPlanner] 附近娱乐查询失败，耗时: {} ms, 错误: {}", 
                    endTime - startTime, e.getMessage());
            return "";
        }
    }

    /**
     * 根据推荐的活动类型确定搜索关键词
     */
    private List<String> determineSearchKeywords(List<String> recommendedActivities) {
        Set<String> keywords = new LinkedHashSet<>();
        
        for (String activity : recommendedActivities) {
            // 户外活动 -> 公园、景点
            if (activity.contains("公园") || activity.contains("徒步") || activity.contains("登山") || 
                activity.contains("骑行") || activity.contains("摄影")) {
                keywords.add("公园");
            }
            
            // 室内运动 -> 健身房、游泳馆
            if (activity.contains("健身") || activity.contains("瑜伽") || activity.contains("攀岩") || 
                activity.contains("游泳")) {
                keywords.add("健身房");
            }
            
            // 文化类 -> 博物馆、美术馆
            if (activity.contains("博物馆") || activity.contains("美术馆")) {
                keywords.add("博物馆");
            }
            
            // 娱乐类 -> 电影院
            if (activity.contains("电影") || activity.contains("KTV") || activity.contains("密室")) {
                keywords.add("电影院");
            }
            
            // 餐饮类 -> 餐厅
            if (activity.contains("烹饪") || activity.contains("烘焙")) {
                keywords.add("美食");
            }
            
            // 购物类 -> 购物中心
            if (activity.contains("购物") || activity.contains("商场")) {
                keywords.add("购物中心");
            }
        }
        
        // 如果没有匹配到特定类型，返回通用关键词（限制为 2 个）
        if (keywords.isEmpty()) {
            keywords.add("美食");
            keywords.add("咖啡厅");
        }
        
        // 限制最多返回 2 个关键词，减少 API 调用次数
        return new ArrayList<>(keywords).subList(0, Math.min(2, keywords.size()));
    }

    /**
     * 根据搜索关键词获取分类标题
     */
    private String getCategoryTitle(String keyword) {
        return switch (keyword) {
            case "公园", "景点" -> "附近户外场所：";
            case "健身房", "游泳馆" -> "附近运动场所：";
            case "博物馆", "美术馆" -> "附近文化场所：";
            case "电影院", "KTV" -> "附近娱乐场所：";
            case "美食" -> "附近特色餐厅：";
            case "咖啡厅" -> "附近咖啡馆：";
            case "购物中心" -> "附近购物中心：";
            case "美容 SPA" -> "附近美容 SPA：";
            default -> "附近" + keyword + "：";
        };
    }

    /**
     * 获取城市中心坐标
     * 优先使用高德地图 API，失败则使用预设坐标
     */
    private String[] getCityCenterCoordinates(String city) {
        // 首先尝试使用高德地图 API
        if (amapApiKey != null && !amapApiKey.isEmpty()) {
            try {
                String[] coords = queryAmapGeocode(city);
                if (coords != null) {
                    return coords;
                }
            } catch (Exception e) {
                // API 调用失败，使用备用方案
            }
        }
        
        // 备用方案：使用常见城市的预设坐标
        return getPredefinedCityCoordinates(city);
    }

    /**
     * 使用高德地图地理编码 API 获取城市坐标
     */
    private String[] queryAmapGeocode(String city) throws Exception {
        String url = String.format(
            "https://restapi.amap.com/v3/geocode/geo?address=%s&key=%s",
            URLEncoder.encode(city, StandardCharsets.UTF_8),
            amapApiKey
        );
        
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .build();
        
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
            String body = response.body();
            // 解析 JSON 响应，提取坐标
            int locIndex = body.indexOf("\"location\":\"");
            if (locIndex != -1) {
                int start = locIndex + "\"location\":".length() + 1;
                int end = body.indexOf("\"", start);
                if (end != -1) {
                    String location = body.substring(start, end);
                    String[] parts = location.split(",");
                    if (parts.length == 2) {
                        // 高德返回的是经度,纬度，需要转换为纬度,经度
                        return new String[]{parts[1], parts[0]};
                    }
                }
            }
        }
        
        return null;
    }

    /**
     * 获取预设的城市坐标
     */
    private String[] getPredefinedCityCoordinates(String city) {
        // 常见城市的中心坐标（纬度，经度）
        if (city.contains("北京")) return new String[]{"39.9042", "116.4074"};
        if (city.contains("上海")) return new String[]{"31.2304", "121.4737"};
        if (city.contains("广州")) return new String[]{"23.1291", "113.2644"};
        if (city.contains("深圳")) return new String[]{"22.5431", "114.0579"};
        if (city.contains("杭州")) return new String[]{"30.2741", "120.1551"};
        if (city.contains("成都")) return new String[]{"30.5728", "104.0668"};
        if (city.contains("武汉")) return new String[]{"30.5928", "114.3055"};
        if (city.contains("西安")) return new String[]{"34.3416", "108.9398"};
        if (city.contains("南京")) return new String[]{"32.0603", "118.7969"};
        if (city.contains("重庆")) return new String[]{"29.4316", "106.9123"};
        
        // 默认返回北京坐标
        return new String[]{"39.9042", "116.4074"};
    }

    /**
     * 使用高德地图 API 搜索周边 POI
     */
    private List<String> searchAmapPOI(double latitude, double longitude, String keyword) {
        long apiStart = System.currentTimeMillis();
        List<String> results = new ArrayList<>();
        
        if (amapApiKey == null || amapApiKey.isEmpty()) {
            return results;
        }
        
        try {
            String url = String.format(
                "https://restapi.amap.com/v3/place/around?location=%.6f,%.6f&keywords=%s&radius=%d&key=%s&offset=5",
                longitude, latitude,  // 注意：高德 API 要求经度在前
                URLEncoder.encode(keyword, StandardCharsets.UTF_8),
                    5000,
                amapApiKey
            );
            
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .build();
            
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                String body = response.body();
                // 简单解析 POI 名称和地址
                parsePoiResults(body, results);
            }
            
            long apiEnd = System.currentTimeMillis();
            log.debug("[高德API] 搜索 {} 耗时: {} ms, 结果数: {}", keyword, apiEnd - apiStart, results.size());
        } catch (Exception e) {
            long apiEnd = System.currentTimeMillis();
            log.warn("[高德API] 搜索 {} 失败，耗时: {} ms, 错误: {}", keyword, apiEnd - apiStart, e.getMessage());
        }
        
        return results;
    }

    /**
     * 解析高德地图 POI 结果
     */
    private void parsePoiResults(String jsonResponse, List<String> results) {
        // 简单解析：提取 name 和 address 字段
        int poisIndex = jsonResponse.indexOf("\"pois\":[");
        if (poisIndex == -1) return;
        
        int startIndex = poisIndex + "\"pois\":".length() + 1;
        int endIndex = jsonResponse.lastIndexOf("]");
        if (endIndex <= startIndex) return;
        
        String poisContent = jsonResponse.substring(startIndex, endIndex);
                
        // 提取每个 POI 的 name 和 address
        String[] pois = poisContent.split("\\{\"");
        for (String poi : pois) {
            if (poi.trim().isEmpty()) continue;
            
            String name = extractJsonField(poi, "name");
            String address = extractJsonField(poi, "address");
            
            if (name != null && !name.isEmpty()) {
                StringBuilder item = new StringBuilder(name);
                if (address != null && !address.isEmpty()) {
                    item.append("（").append(address).append("）");
                }
                results.add(item.toString());
            }
        }
    }

    /**
     * 从 JSON 片段中提取字段值
     */
    private String extractJsonField(String json, String fieldName) {
        String pattern = "\"" + fieldName + "\":\"";
        int index = json.indexOf(pattern);
        if (index == -1) return null;
        
        int start = index + pattern.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return null;
        
        return json.substring(start, end);
    }

    /**
     * 根据温度提供衣物建议
     */
    private String getClothingAdvice(int temperature) {
        if (temperature >= 30) {
            return "炎热，穿短袖短裤，注意防暑";
        } else if (temperature >= 25) {
            return "温暖，穿短袖 T 恤、薄衬衫";
        } else if (temperature >= 20) {
            return "适宜，穿长袖 T 恤、薄外套";
        } else if (temperature >= 15) {
            return "稍凉，穿夹克、风衣，备外套";
        } else if (temperature >= 10) {
            return "较凉，穿毛衣、厚外套";
        } else if (temperature >= 5) {
            return "寒冷，穿羽绒服、棉衣";
        } else if (temperature >= 0) {
            return "很冷，穿厚羽绒服、保暖内衣";
        } else {
            return "极寒，穿加厚羽绒服，减少外出";
        }
    }

    /**
     * 从活动列表中随机选择指定数量的活动
     */
    private List<String> selectRandomActivities(List<String> activities) {
        List<String> shuffled = new ArrayList<>(activities);
        Collections.shuffle(shuffled);
        return shuffled.subList(0, Math.min(3, shuffled.size()));
    }

    /**
     * 解析天气条件
     */
    private WeatherCondition parseWeatherCondition(String weatherInfo) {
        WeatherCondition condition = new WeatherCondition();
        
        // 提取温度
        condition.temperature = extractTemperature(weatherInfo);
        
        // 判断天气类型
        condition.type = determineWeatherType(weatherInfo);
        condition.description = extractWeatherDescription(weatherInfo);
        
        return condition;
    }

    /**
     * 从天气信息中提取温度
     */
    private int extractTemperature(String weatherInfo) {
        // 尝试匹配 "温度：XX°C" 或 "temp_C":"XX" 等格式
        String[] patterns = {"温度：", "temp_C\":\"", "Temp:", "气温:"};
        
        for (String pattern : patterns) {
            int index = weatherInfo.indexOf(pattern);
            if (index != -1) {
                int start = index + pattern.length();
                int end = start;
                while (end < weatherInfo.length() && Character.isDigit(weatherInfo.charAt(end))) {
                    end++;
                }
                if (end > start) {
                    return Integer.parseInt(weatherInfo.substring(start, end));
                }
            }
        }
        
        // 默认返回 22 度
        return 22;
    }

    /**
     * 判断天气类型
     */
    private WeatherType determineWeatherType(String weatherInfo) {
        String lowerInfo = weatherInfo.toLowerCase();
        
        // 检查是否有雨
        if (lowerInfo.contains("雨") || lowerInfo.contains("rain")) {
            return WeatherType.RAINY;
        }
        
        // 检查是否有雪
        if (lowerInfo.contains("雪") || lowerInfo.contains("snow")) {
            return WeatherType.SNOWY;
        }
        
        // 检查是否晴朗
        if (lowerInfo.contains("晴") || lowerInfo.contains("sun") || lowerInfo.contains("clear")) {
            return WeatherType.SUNNY;
        }
        
        // 检查是否多云/阴
        if (lowerInfo.contains("云") || lowerInfo.contains("cloud") || 
            lowerInfo.contains("阴") || lowerInfo.contains("overcast")) {
            return WeatherType.CLOUDY;
        }
        
        return WeatherType.UNKNOWN;
    }

    /**
     * 提取天气描述
     */
    private String extractWeatherDescription(String weatherInfo) {
        // 尝试提取天气描述
        String[] patterns = {"天气：", "weatherDesc\":[{\"value\":\""};
        
        for (String pattern : patterns) {
            int index = weatherInfo.indexOf(pattern);
            if (index != -1) {
                int start = index + pattern.length();
                int end = weatherInfo.indexOf("\"", start);
                if (end == -1) end = weatherInfo.indexOf("\n", start);
                if (end != -1 && end > start) {
                    return weatherInfo.substring(start, end).trim();
                }
            }
        }
        
        return null;
    }

    /**
     * 天气条件内部类
     */
    private static class WeatherCondition {
        int temperature = 22;
        WeatherType type = WeatherType.UNKNOWN;
        String description;
    }

    /**
     * 天气类型枚举
     */
    private enum WeatherType {
        SUNNY,      // 晴朗
        RAINY,      // 雨天
        SNOWY,      // 雪天
        CLOUDY,     // 多云
        OVERCAST,   // 阴天
        UNKNOWN     // 未知
    }
}
