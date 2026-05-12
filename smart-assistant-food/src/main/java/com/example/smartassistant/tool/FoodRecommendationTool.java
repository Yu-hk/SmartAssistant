/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.tool;

import com.example.smartassistant.knowledge.SpecialtyCuisineKnowledgeBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 美食推荐工具
 * 基于高德地图 API 推荐附近的美食餐厅
 */
@Component
public class FoodRecommendationTool {

    private static final Logger log = LoggerFactory.getLogger(FoodRecommendationTool.class);

    @Value("${amap.api.key:}")
    private String amapApiKey;

    private final SpecialtyCuisineKnowledgeBase knowledgeBase;
    
    // ⭐ 城市坐标配置（从 JSON 文件加载）
    private final Map<String, double[]> cityCoordinates = new HashMap<>();
    private double[] defaultCoordinates = {39.9042, 116.4074}; // 默认北京坐标

    public FoodRecommendationTool(SpecialtyCuisineKnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
        loadCityCoordinates();  // ⭐ 加载城市坐标配置
    }
    
    /**
     * ⭐ 从 JSON 配置文件加载城市坐标
     */
    private void loadCityCoordinates() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ClassPathResource resource = new ClassPathResource("city-coordinates.json");
            JsonNode root = mapper.readTree(resource.getInputStream());
            
            // 加载城市坐标
            JsonNode citiesNode = root.get("cities");
            if (citiesNode != null && citiesNode.isObject()) {
                citiesNode.fields().forEachRemaining(entry -> {
                    String key = entry.getKey();
                    JsonNode cityData = entry.getValue();
                    double lat = cityData.get("lat").asDouble();
                    double lon = cityData.get("lon").asDouble();
                    cityCoordinates.put(key, new double[]{lat, lon});
                });
                log.info("[FoodRecommendation] ✅ 加载 {} 个城市坐标", cityCoordinates.size());
            }
            
            // 加载默认坐标
            JsonNode defaultNode = root.get("default");
            if (defaultNode != null) {
                defaultCoordinates = new double[]{
                    defaultNode.get("lat").asDouble(),
                    defaultNode.get("lon").asDouble()
                };
            }
            
        } catch (IOException e) {
            log.error("[FoodRecommendation] ❌ 加载城市坐标配置失败: {}", e.getMessage());
            // 使用默认值，不影响服务启动
        }
    }

    /**
     * 根据位置和口味偏好推荐美食
     *
     * @param city 城市名称
     * @param cuisineType 菜系类型（如：川菜、粤菜、日料等）
     * @param coordinates 坐标（纬度,经度），可选
     * @return 美食推荐列表
     */
    @Tool(description = "根据城市、菜系类型和位置推荐附近的美食餐厅")
    public String recommendFood(String city, String cuisineType, String coordinates) {
        long startTime = System.currentTimeMillis();
        log.info("[FoodRecommendation] 开始推荐美食，城市: {}, 菜系: {}, 坐标: {}", 
                city, cuisineType, coordinates);

        try {
            double latitude, longitude;

            // 解析坐标
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

            // 搜索附近餐厅
            List<String> restaurants = searchNearbyRestaurants(latitude, longitude, cuisineType);

            // 生成推荐结果
            StringBuilder result = new StringBuilder();
            result.append("🍽️ 【").append(city).append("】美食推荐\n");
            
            if (cuisineType != null && !cuisineType.isEmpty()) {
                result.append("菜系：").append(cuisineType).append("\n");
            }
            
            result.append("\n推荐餐厅：\n");

            if (restaurants.isEmpty()) {
                result.append("暂无相关餐厅信息，请尝试其他菜系或扩大搜索范围");
            } else {
                for (int i = 0; i < Math.min(5, restaurants.size()); i++) {
                    result.append(restaurants.get(i)).append("\n");
                }
            }

            long endTime = System.currentTimeMillis();
            log.info("[FoodRecommendation] 推荐完成，耗时: {} ms, 找到 {} 家餐厅", 
                    endTime - startTime, restaurants.size());

            return result.toString();

        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            log.error("[FoodRecommendation] 推荐失败，耗时: {} ms, 错误: {}", 
                    endTime - startTime, e.getMessage(), e);
            return "美食推荐失败：" + e.getMessage();
        }
    }

    /**
     * 搜索附近的餐厅
     */
    private List<String> searchNearbyRestaurants(double latitude, double longitude, String cuisineType) {
        List<String> results = new ArrayList<>();

        if (amapApiKey == null || amapApiKey.isEmpty()) {
            log.warn("[FoodRecommendation] 未配置高德 API Key");
            return results;
        }

        try {
            // 构建搜索关键词
            String keyword = cuisineType != null && !cuisineType.isEmpty() 
                    ? cuisineType + " 餐厅" 
                    : "美食";

            String url = String.format(
                "https://restapi.amap.com/v3/place/around?location=%.6f,%.6f&keywords=%s&radius=3000&key=%s&offset=10",
                longitude, latitude,  // 高德 API 要求经度在前
                URLEncoder.encode(keyword, StandardCharsets.UTF_8),
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
                parseRestaurantResults(body, latitude, longitude, results);
            }

        } catch (Exception e) {
            log.warn("[FoodRecommendation] 搜索餐厅失败: {}", e.getMessage());
        }

        return results;
    }

    /**
     * 解析餐厅搜索结果
     */
    private void parseRestaurantResults(String jsonResponse, double userLat, double userLon, List<String> results) {
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
            String rating = extractJsonField(poi, "biz_ext");

            if (name != null && !name.isEmpty()) {
                StringBuilder item = new StringBuilder();
                
                // 添加序号和名称
                item.append("• ").append(name);

                // 添加地址
                if (address != null && !address.isEmpty()) {
                    item.append("\n  📍 ").append(address);
                }

                // 计算距离
                if (location != null && location.contains(",")) {
                    String[] coords = location.split(",");
                    if (coords.length == 2) {
                        try {
                            double poiLon = Double.parseDouble(coords[0]);
                            double poiLat = Double.parseDouble(coords[1]);
                            double distance = calculateDistance(userLat, userLon, poiLat, poiLon);

                            if (distance < 1000) {
                                item.append(" | 距离 ").append(String.format("%.0f", distance)).append("m");
                            } else {
                                item.append(" | 距离 ").append(String.format("%.1f", distance / 1000)).append("km");
                            }
                        } catch (NumberFormatException e) {
                            // 忽略距离计算错误
                        }
                    }
                }

                results.add(item.toString());
            }

            // 限制返回数量
            if (results.size() >= 10) break;
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
     * 获取城市中心坐标（⭐ 从配置文件读取）
     */
    private String[] getCityCenterCoordinates(String city) {
        // 遍历配置的城市列表，查找匹配项
        for (Map.Entry<String, double[]> entry : cityCoordinates.entrySet()) {
            if (city.contains(entry.getKey())) {
                double[] coords = entry.getValue();
                return new String[]{String.valueOf(coords[0]), String.valueOf(coords[1])};
            }
        }
        
        // 未找到匹配，返回默认坐标
        log.warn("[FoodRecommendation] 未找到城市 '{}' 的坐标，使用默认北京坐标", city);
        return new String[]{String.valueOf(defaultCoordinates[0]), String.valueOf(defaultCoordinates[1])};
    }

    /**
     * 查询全国特色菜知识库
     * 根据城市、省份、口味或菜名搜索特色菜品信息
     *
     * @param query 查询关键词（可以是城市名、省份名、口味、菜名等）
     * @return 特色菜信息列表
     */
    @Tool(description = "查询全国各地的特色菜知识库，支持按城市、省份、口味或菜名搜索")
    public String querySpecialtyCuisine(String query) {
        long startTime = System.currentTimeMillis();
        
        log.info("[FoodKnowledgeBase] 开始查询特色菜，关键词: {}", query);

        try {
            if (query == null || query.isEmpty()) {
                return "请提供查询关键词，例如：北京、四川、麻辣、烤鸭等";
            }

            List<SpecialtyCuisineKnowledgeBase.SpecialtyDish> results;
            String title;

            // 判断查询类型
            String normalizedQuery = normalizeLocation(query);
            
            if (knowledgeBase.getAllCities().contains(normalizedQuery)) {
                // 按城市查询
                results = knowledgeBase.getDishesByCity(normalizedQuery);
                title = normalizedQuery + "特色菜";
            } else if (knowledgeBase.getAllProvinces().contains(normalizedQuery)) {
                // 按省份查询
                results = knowledgeBase.getDishesByProvince(normalizedQuery);
                title = normalizedQuery + "特色菜";
            } else if (normalizedQuery.equals("麻辣") || normalizedQuery.equals("香辣") || 
                      normalizedQuery.equals("清淡") || normalizedQuery.equals("酸甜") || 
                      normalizedQuery.equals("鲜香")) {
                // 按口味查询
                results = knowledgeBase.getDishesByTaste(normalizedQuery);
                title = normalizedQuery + "口味特色菜";
            } else {
                // 模糊搜索
                results = knowledgeBase.searchDishes(query);
                title = "搜索【" + query + "】结果";
            }

            String formattedResult = knowledgeBase.formatDishesList(results, title);

            long endTime = System.currentTimeMillis();
            log.info("[FoodKnowledgeBase] 查询完成，耗时: {} ms, 找到 {} 道菜品", 
                    endTime - startTime, results.size());

            return formattedResult;

        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            log.error("[FoodKnowledgeBase] 查询失败，耗时: {} ms, 错误: {}", 
                    endTime - startTime, e.getMessage(), e);
            return "特色菜查询失败：" + e.getMessage();
        }
    }

    /**
     * 地点名称标准化
     * 去除“省”、“市”等后缀，统一格式
     */
    private String normalizeLocation(String location) {
        if (location == null || location.isEmpty()) {
            return location;
        }
        
        String normalized = location.trim();
        
        // 去除常见后缀（按长度从长到短匹配）
        normalized = normalized.replaceAll("特别行政区$", "");
        normalized = normalized.replaceAll("壮族自治区$", "");
        normalized = normalized.replaceAll("回族自治区$", "");
        normalized = normalized.replaceAll("维吾尔自治区$", "");
        normalized = normalized.replaceAll("自治区$", "");
        normalized = normalized.replaceAll("([省市])$", "");
        
        // 特殊处理：直辖市保留原名

        return normalized;
    }

    /**
     * 获取所有可查询的城市列表
     *
     * @return 城市列表
     */
    @Tool(description = "获取知识库中所有可查询的城市列表")
    public String listAvailableCities() {
        log.info("[FoodKnowledgeBase] 获取所有城市列表");
        
        List<String> cities = new ArrayList<>(knowledgeBase.getAllCities());
        cities.sort(String::compareTo);
        
        StringBuilder sb = new StringBuilder();
        sb.append("🌆 【可查询的城市列表】\n\n");
        
        for (int i = 0; i < cities.size(); i++) {
            sb.append("• ").append(cities.get(i));
            if ((i + 1) % 5 == 0 || i == cities.size() - 1) {
                sb.append("\n");
            } else {
                sb.append(" | ");
            }
        }
        
        sb.append("\n共 ").append(cities.size()).append(" 个城市");
        sb.append("\n\n💡 提示：您可以查询任意城市的特色菜，例如：\"某城市特色菜\"");
        
        return sb.toString();
    }

    /**
     * 获取所有可查询的口味分类
     *
     * @return 口味分类列表
     */
    @Tool(description = "获取知识库中所有可查询的口味分类")
    public String listAvailableTastes() {
        log.info("[FoodKnowledgeBase] 获取所有口味分类");

        return """
                👅 【可查询的口味分类】
                
                • 麻辣 - 川菜、湘菜等重口味
                • 香辣 - 辣味适中，香气浓郁
                • 清淡 - 粤菜、淮扬菜等清淡口味
                • 酸甜 - 江浙菜系常见口味
                • 鲜香 - 突出食材本味
                
                💡 提示：您可以按口味查询特色菜，例如："麻辣口味的菜"、"清淡的美食\"""";
    }
}
