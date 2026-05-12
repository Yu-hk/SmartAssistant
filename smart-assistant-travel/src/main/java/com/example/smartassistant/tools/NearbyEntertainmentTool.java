/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.tools;

import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * 附近娱乐项目推荐工具
 * 
 * <p>基于用户当前位置，搜索附近的娱乐项目和活动场所</p>
 * 
 * <p>功能特点：</p>
 * <ul>
 *     <li>自动获取用户当前位置（通过 IP 地址）</li>
 *     <li>搜索指定半径内的娱乐场所</li>
 *     <li>提供多种类型的娱乐项目推荐</li>
 *     <li>支持自定义搜索半径</li>
 * </ul>
 */
@Setter
@Component
public class NearbyEntertainmentTool {

    private static final Logger log = LoggerFactory.getLogger(NearbyEntertainmentTool.class);

    /**
     * -- SETTER --
     *  设置高德地图 API Key
     *
     */
    // 高德地图 API Key
    private String amapApiKey;

    // 娱乐项目 POI 类型映射
    private static final List<String> ENTERTAINMENT_KEYWORDS = List.of(
        "餐厅",      // 餐厅
        "咖啡厅",    // 咖啡馆
        "酒吧",      // 酒吧
        "电影院",    // 电影院
        "健身房",    // 健身房
        "公园",      // 公园
        "博物馆",    // 博物馆
        "购物中心",  // 购物中心
        "KTV",      // KTV
        "保龄球",    // 保龄球馆
        "游乐园",    // 游乐园
        "动物园",    // 动物园
        "美术馆",    // 美术馆
        "图书馆",    // 图书馆
        "美容 SPA"   // SPA 中心
    );

    /**
     * 获取用户当前位置并根据指定半径搜索附近的娱乐项目
     *
     * @param radius 搜索半径（米），例如：1000=1 公里，5000=5 公里
     * @return 附近娱乐项目的推荐列表
     */
    @Tool(description = "获取用户当前所在位置，并在指定距离范围内搜索娱乐场所。radius 参数单位为米，如 1000 表示搜索 1 公里范围内的场所")
    public String findNearbyEntertainment(int radius) {
        log.info("[NearbyEntertainmentTool] 调用 findNearbyEntertainment, 参数: radius={}米", radius);
        
        try {
            // 第一步：获取用户位置
            String locationInfo = getCurrentLocation();
            String city = extractCityFromLocation(locationInfo);
            
            // 第二步：搜索附近娱乐项目
            return searchEntertainmentByCity(city, radius);
            
        } catch (Exception e) {
            return "抱歉，暂时无法获取您的位置信息或搜索附近娱乐项目。请稍后重试。";
        }
    }

    /**
     * 获取城市中心坐标（带备用方案）
     * 如果高德地图 API 失败，使用预设的城市坐标
     */
    private String[] getCityCenterCoordinatesWithFallback(String city) {
        // 首先尝试使用高德地图 API
        try {
            String[] coords = getCityCenterCoordinates(city);
            if (coords != null) {
                return coords;
            }
        } catch (Exception e) {
            // API 调用失败，使用备用方案
        }
        
        // 备用方案：使用常见城市的预设坐标
        return getPredefinedCityCoordinates(city);
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
     * 获取当前所在位置信息
     */
    private String getCurrentLocation() throws Exception {
        String url = "http://ip-api.com/json/?lang=zh-CN";

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .build();

        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return response.body();
        } else {
            throw new RuntimeException("位置查询失败：" + response.statusCode());
        }
    }

    /**
     * 从位置信息中提取城市名称
     */
    private String extractCityFromLocation(String jsonResponse) {
        int index = jsonResponse.indexOf("\"city\":\"");
        if (index == -1) return "北京市"; // 默认值

        int start = index + "\"city\":".length() + 1; // +1 for opening quote
        int end = jsonResponse.indexOf("\"", start);
        
        if (end == -1 || end <= start) return "北京市";
        
        return jsonResponse.substring(start, end);
    }

    /**
     * 根据城市和半径搜索娱乐项目
     * 使用高德地图 API 搜索周边 POI 数据
     * 
     * @param city 城市名称
     * @param radius 搜索半径（米）
     * @return 娱乐项目推荐列表
     */
    private String searchEntertainmentByCity(String city, int radius) {
        try {
            // 第一步：获取城市中心坐标
            String[] centerCoords = getCityCenterCoordinates(city);
            if (centerCoords == null) {
                return "抱歉，未能找到" + city + "的地理位置信息。";
            }
            
            double latitude = Double.parseDouble(centerCoords[0]);
            double longitude = Double.parseDouble(centerCoords[1]);
            
            // 第二步：使用高德地图 API 搜索周边娱乐设施
            StringBuilder result = new StringBuilder();
            result.append("【").append(city).append("】附近娱乐项目推荐\n");
            result.append("搜索范围：").append(String.format("%.1f", radius / 1000.0)).append("公里\n\n");
            
            // 搜索不同类型的娱乐场所
            List<String> restaurants = searchAmapPOI(latitude, longitude, radius, "美食");
            List<String> cafes = searchAmapPOI(latitude, longitude, radius, "咖啡厅");
            List<String> activities = searchAmapPOI(latitude, longitude, radius, "休闲娱乐");
            
            if (!restaurants.isEmpty()) {
                result.append("🍽️  特色餐厅：\n");
                for (int i = 0; i < Math.min(3, restaurants.size()); i++) {
                    result.append("   ").append(i + 1).append(". ").append(restaurants.get(i)).append("\n");
                }
            }
            
            if (!cafes.isEmpty()) {
                result.append("\n☕  休闲咖啡馆：\n");
                for (int i = 0; i < Math.min(2, cafes.size()); i++) {
                    result.append("   ").append(i + 1).append(". ").append(cafes.get(i)).append("\n");
                }
            }
            
            if (!activities.isEmpty()) {
                result.append("\n🎯  娱乐活动：\n");
                for (int i = 0; i < Math.min(3, activities.size()); i++) {
                    result.append("   ").append(i + 1).append(". ").append(activities.get(i)).append("\n");
                }
            }
            
            result.append("\n💡 提示：以上场所信息仅供参考，实际距离和营业时间请以实地为准。");
            
            return result.toString();
            
        } catch (Exception e) {
            return "抱歉，搜索附近娱乐项目时出现错误：" + e.getMessage();
        }
    }

    /**
     * 获取城市中心坐标
     * 使用高德地图地理编码 API
     */
    private String[] getCityCenterCoordinates(String city) throws Exception {
        String url = String.format(
            "https://restapi.amap.com/v3/geocode/geo?address=%s&key=%s",
            city,
            amapApiKey
        );
        
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
            // 解析 JSON 响应，提取经纬度
            String json = response.body();
            int locationIndex = json.indexOf("\"location\":\"");
            if (locationIndex != -1) {
                int start = locationIndex + "\"location\":\"".length();
                int end = json.indexOf("\"", start);
                String location = json.substring(start, end);
                String[] parts = location.split(",");
                if (parts.length == 2) {
                    return new String[]{parts[1], parts[0]}; // 返回 [纬度，经度]
                }
            }
        }
        
        return null;
    }
    
    /**
     * 使用高德地图 API 搜索周边 POI
     * 
     * @param latitude 纬度
     * @param longitude 经度
     * @param radius 搜索半径（米）
     * @param keywords 搜索关键词
     * @return POI 列表
     */
    private List<String> searchAmapPOI(double latitude, double longitude, int radius, String keywords) throws Exception {
        String url = String.format(
            "https://restapi.amap.com/v5/place/text?keywords=%s&location=%.6f,%.6f&radius=%d&key=%s",
            keywords,
            longitude,
            latitude,
            radius,
            amapApiKey
        );
        
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
        
        List<String> results = new ArrayList<>();
        
        if (response.statusCode() == 200) {
            String json = response.body();
            
            // 简单解析 JSON，提取 POI 名称和地址
            int poisStart = json.indexOf("\"pois\":[");
            if (poisStart != -1) {
                int poisEnd = json.indexOf("]", poisStart);
                String poisJson = json.substring(poisStart, poisEnd + 1);
                
                // 提取前几个 POI 的名称
                int count = 0;
                int index = 0;
                while (count < 5 && index < poisJson.length()) {
                    int nameStart = poisJson.indexOf("\"name\":\"", index);
                    if (nameStart == -1) break;
                    
                    int nameBegin = nameStart + "\"name\":\"".length();
                    int nameEnd = poisJson.indexOf("\"", nameBegin);
                    String name = poisJson.substring(nameBegin, nameEnd);
                    
                    // 尝试获取地址
                    String address = "";
                    int addrStart = poisJson.indexOf("\"address\":\"", nameBegin);
                    if (addrStart != -1 && addrStart < nameEnd + 100) {
                        int addrBegin = addrStart + "\"address\":\"".length();
                        int addrEnd = poisJson.indexOf("\"", addrBegin);
                        address = poisJson.substring(addrBegin, addrEnd);
                    }
                    
                    // 尝试获取距离
                    String distance = "";
                    int distStart = poisJson.indexOf("\"distance\":\"", nameBegin);
                    if (distStart != -1 && distStart < nameEnd + 100) {
                        int distBegin = distStart + "\"distance\":\"".length();
                        int distEnd = poisJson.indexOf("\"", distBegin);
                        distance = poisJson.substring(distBegin, distEnd);
                    }
                    
                    StringBuilder poiInfo = new StringBuilder();
                    poiInfo.append(name);
                    if (!address.isEmpty()) {
                        poiInfo.append("（").append(address).append("）");
                    }
                    if (!distance.isEmpty()) {
                        poiInfo.append(" - 距离").append(distance);
                    }
                    
                    results.add(poiInfo.toString());
                    count++;
                    index = nameEnd + 1;
                }
            }
        }
        
        return results;
    }

}
