/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.data;

import com.example.smartassistant.entity.TouristAttraction;
import com.example.smartassistant.mapper.TouristAttractionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 高德地图POI数据同步服务
 * 从高德地图API获取真实景点数据并导入数据库
 */
@Service
public class AmapPoiSyncService {

    private static final Logger log = LoggerFactory.getLogger(AmapPoiSyncService.class);

    @Value("${amap.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final TouristAttractionMapper mapper;
    private final DataQualityValidator qualityValidator;

    public AmapPoiSyncService(TouristAttractionMapper mapper,
                             DataQualityValidator qualityValidator) {
        this.restTemplate = new RestTemplate();
        this.mapper = mapper;
        this.qualityValidator = qualityValidator;
    }

    /**
     * 同步指定城市的景点POI数据
     *
     * @param city 城市名称
     * @param poiType POI类型（旅游景点: 110000）
     * @return 导入的景点数量
     */
    @Transactional
    public int syncCityPOI(String city, String poiType) {
        log.info("[AmapSync] 开始同步 {} 的POI数据, 类型: {}", city, poiType);

        try {
            // 调用高德地图POI搜索API
            String url = String.format(
                "https://restapi.amap.com/v3/place/text?keywords=&city=%s&types=%s&output=json&key=%s&pagesize=20&page=1",
                city, poiType, apiKey
            );

            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            Map<String, Object> result = response.getBody();

            if (result == null || !"1".equals(result.get("status"))) {
                log.error("[AmapSync] API调用失败: {}", result != null ? result.get("info") : "无响应");
                return 0;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pois = (List<Map<String, Object>>) result.get("pois");

            if (pois == null || pois.isEmpty()) {
                log.warn("[AmapSync] 未找到 {} 的POI数据", city);
                return 0;
            }

            int imported = 0;
            for (Map<String, Object> poi : pois) {
                TouristAttraction attraction = convertToAttraction(poi, city);
                
                // 数据质量验证
                if (qualityValidator.validate(attraction)) {
                    // 检查是否已存在
                    var existing = mapper.findByNameAndCity(attraction.getName(), attraction.getCity());
                    if (existing == null) {
                        mapper.insert(attraction);
                        imported++;
                        log.debug("[AmapSync] 导入景点: {}", attraction.getName());
                    }
                } else {
                    log.warn("[AmapSync] 数据验证失败，跳过: {}", poi.get("name"));
                }
            }

            log.info("[AmapSync] 同步完成，成功导入 {} 个景点", imported);
            return imported;

        } catch (Exception e) {
            log.error("[AmapSync] 同步失败: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 批量同步多个城市
     */
    @Transactional
    public Map<String, Integer> batchSyncCities(List<String> cities) {
        log.info("[AmapSync] 开始批量同步 {} 个城市", cities.size());
        
        Map<String, Integer> results = new HashMap<>();
        int totalImported = 0;

        for (String city : cities) {
            try {
                int count = syncCityPOI(city, "110000"); // 旅游景点类型
                results.put(city, count);
                totalImported += count;
                
                // 避免API限流，暂停1秒
                Thread.sleep(1000);
                
            } catch (Exception e) {
                log.error("[AmapSync] 同步城市 {} 失败: {}", city, e.getMessage());
                results.put(city, -1);
            }
        }

        log.info("[AmapSync] 批量同步完成，共导入 {} 个景点", totalImported);
        return results;
    }

    /**
     * 将高德POI数据转换为景点实体
     */
    private TouristAttraction convertToAttraction(Map<String, Object> poi, String city) {
        String name = (String) poi.getOrDefault("name", "未知景点");
        String address = (String) poi.getOrDefault("address", "");
        String location = (String) poi.getOrDefault("location", "");
        String type = (String) poi.getOrDefault("type", "");
        String bizType = (String) poi.getOrDefault("biz_type", "");

        // 解析坐标
        Double latitude = null;
        Double longitude = null;
        if (location != null && location.contains(",")) {
            String[] coords = location.split(",");
            try {
                longitude = Double.parseDouble(coords[0]);
                latitude = Double.parseDouble(coords[1]);
            } catch (NumberFormatException e) {
                log.warn("[AmapSync] 坐标解析失败: {}", location);
            }
        }

        // 确定省份（简化处理，实际应使用地理编码API）
        String province = determineProvince(city);

        // 构建标签
        List<String> tags = extractTags(type);

        TouristAttraction attraction = new TouristAttraction();
        attraction.setName(name);
        attraction.setCity(city);
        attraction.setProvince(province);
        attraction.setDescription(address != null && !address.isEmpty() ? address : "暂无描述");
        attraction.setLevel(determineLevel(bizType));
        attraction.setTicketPrice(null); // 门票价格需要从其他API获取
        attraction.setOpenTime("全天开放"); // 开放时间需要具体查询
        attraction.setSuggestDuration(120); // 默认建议游玩2小时
        attraction.setTags(tags);
        attraction.setHighlights(List.of("高德地图POI"));
        attraction.setLatitude(latitude);
        attraction.setLongitude(longitude);
        
        return attraction;
    }

    /**
     * 根据城市确定省份
     */
    private String determineProvince(String city) {
        // 简化的映射表，实际应使用地理编码API
        Map<String, String> cityProvinceMap = new HashMap<>();
        cityProvinceMap.put("北京", "北京");
        cityProvinceMap.put("上海", "上海");
        cityProvinceMap.put("天津", "天津");
        cityProvinceMap.put("重庆", "重庆");
        cityProvinceMap.put("广州", "广东");
        cityProvinceMap.put("深圳", "广东");
        cityProvinceMap.put("成都", "四川");
        cityProvinceMap.put("杭州", "浙江");
        cityProvinceMap.put("南京", "江苏");
        cityProvinceMap.put("武汉", "湖北");
        cityProvinceMap.put("西安", "陕西");
        cityProvinceMap.put("长沙", "湖南");
        cityProvinceMap.put("厦门", "福建");
        cityProvinceMap.put("青岛", "山东");
        cityProvinceMap.put("大连", "辽宁");
        cityProvinceMap.put("昆明", "云南");
        cityProvinceMap.put("桂林", "广西");
        cityProvinceMap.put("三亚", "海南");
        cityProvinceMap.put("丽江", "云南");
        cityProvinceMap.put("哈尔滨", "黑龙江");
        cityProvinceMap.put("贵阳", "贵州");
        cityProvinceMap.put("兰州", "甘肃");
        
        return cityProvinceMap.getOrDefault(city, "未知");
    }

    /**
     * 从POI类型提取标签
     */
    private List<String> extractTags(String type) {
        List<String> tags = new ArrayList<>();
        
        if (type == null) return tags;

        if (type.contains("风景名胜")) tags.add("自然");
        if (type.contains("公园")) tags.add("公园");
        if (type.contains("博物馆")) tags.add("博物馆");
        if (type.contains("文物古迹")) tags.add("历史");
        if (type.contains("宗教场所")) tags.add("文化");
        if (type.contains("购物中心")) tags.add("购物");
        if (type.contains("美食")) tags.add("美食");

        return tags.isEmpty() ? List.of("景点") : tags;
    }

    /**
     * 确定景点等级
     */
    private String determineLevel(String bizType) {
        if (bizType == null) return "未评级";
        if (bizType.contains("5A")) return "5A";
        if (bizType.contains("4A")) return "4A";
        if (bizType.contains("3A")) return "3A";
        return "未评级";
    }

    /**
     * 获取同步统计信息
     */
    public Map<String, Object> getSyncStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalAttractions", mapper.selectCount(null));
        stats.put("cities", mapper.findAllCities().size());
        stats.put("provinces", mapper.findAllProvinces().size());
        
        // 数据质量统计
        stats.put("withCoordinates", countWithCoordinates());
        stats.put("withDescription", countWithDescription());
        stats.put("qualityScore", calculateQualityScore());
        
        return stats;
    }

    /**
     * 统计有坐标的景点数量
     */
    private long countWithCoordinates() {
        return mapper.selectList(null).stream()
            .filter(a -> a.getLatitude() != null && a.getLongitude() != null)
            .count();
    }

    /**
     * 统计有描述的景点数量
     */
    private long countWithDescription() {
        return mapper.selectList(null).stream()
            .filter(a -> a.getDescription() != null && !a.getDescription().isEmpty() 
                    && !a.getDescription().equals("暂无描述"))
            .count();
    }

    /**
     * 计算数据质量分数（0-100）
     */
    private double calculateQualityScore() {
        long total = mapper.selectCount(null);
        if (total == 0) return 0;

        long withCoords = countWithCoordinates();
        long withDesc = countWithDescription();
        long withTags = mapper.selectList(null).stream()
            .filter(a -> a.getTags() != null && !a.getTags().isEmpty())
            .count();

        // 权重：坐标30%，描述30%，标签20%，等级20%
        double score = (withCoords * 30.0 + withDesc * 30.0 + withTags * 20.0) / total;
        
        // 加上等级分
        long withLevel = mapper.selectList(null).stream()
            .filter(a -> a.getLevel() != null && !a.getLevel().equals("未评级"))
            .count();
        score += (withLevel * 20.0) / total;

        return Math.round(score * 100.0) / 100.0;
    }
}
