/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 天气查询工具 - 使用高德地图 API
 *
 * <p>通过高德官方 API 查询天气，数据更准确、稳定
 *
 * <p>使用示例：
 * - 北京天气：query("北京")
 * - 上海天气：query("上海")
 */
@Slf4j
@Component
public class WeatherTool {
    
    @Value("${amap.api.key}")
    private String amapApiKey;

    /**
     * 查询指定城市的天气信息
     *
     * @param city 城市名称，如“北京”、“上海”、“深圳”
     * @return 天气信息字符串
     */
    @Tool(description = "查询指定城市的天气信息，输入城市名称返回天气状况、温度等信息")
    public String query(String city) {
        log.info("[WeatherTool] 调用高德 API 查询天气, 参数: city={}", city);
        
        try {
            // 调用高德天气 API
            String url = String.format(
                "https://restapi.amap.com/v3/weather/weatherInfo?city=%s&key=%s&extensions=all",
                city, amapApiKey
            );
            
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                String result = parseAmapWeatherResponse(response.body(), city);
                log.info("[WeatherTool] ✅ 高德 API 天气查询成功");
                return result;
            } else {
                log.warn("[WeatherTool] ❌ 高德 API 返回错误码: {}", response.statusCode());
                return "⚠️ 天气服务暂时不可用，请稍后重试。";
            }
            
        } catch (Exception e) {
            log.error("[WeatherTool] ❌ 高德 API 天气查询失败: {}", e.getMessage());
            return "⚠️ 天气服务暂时不可用，请稍后重试。";
        }
    }
    
    /**
     * 解析高德天气 API 返回的 JSON 数据
     */
    private String parseAmapWeatherResponse(String jsonResponse, String city) {
        try {
            // 简单解析 JSON，提取天气预报
            StringBuilder result = new StringBuilder();
            result.append("【").append(city).append("】天气预报\n");
            result.append("━━━━━━━━━━━━━━━━━━━━\n");
            
            // 提取 forecasts 数组
            int forecastsIndex = jsonResponse.indexOf("\"forecasts\":[");
            if (forecastsIndex == -1) {
                return "⚠️ 天气数据解析失败";
            }
            
            // 提取第一个预报（今天）
            int forecastStart = jsonResponse.indexOf("{", forecastsIndex);
            int forecastEnd = jsonResponse.indexOf("}", forecastStart);
            String forecast = jsonResponse.substring(forecastStart, forecastEnd + 1);
            
            // 提取日期
            String date = extractJsonValue(forecast, "\"date\":\"");
            // 提取星期
            String week = extractJsonValue(forecast, "\"week\":\"");
            // 提取白天天气
            String dayWeather = extractJsonValue(forecast, "\"dayweather\":\"");
            // 提取夜间天气
            String nightWeather = extractJsonValue(forecast, "\"nightweather\":\"");
            // 提取白天温度
            String dayTemp = extractJsonValue(forecast, "\"daytemp\":\"");
            // 提取夜间温度
            String nightTemp = extractJsonValue(forecast, "\"nighttemp\":\"");
            // 提取风向
            String dayWind = extractJsonValue(forecast, "\"daywind\":\"");
            // 提取风力
            String dayPower = extractJsonValue(forecast, "\"daypower\":\"");
            
            result.append("📅 日期：").append(date).append(" (").append(week).append(")\n");
            result.append("☀️  白天：").append(dayWeather).append(" ").append(dayTemp).append("°C\n");
            result.append("🌙 夜间：").append(nightWeather).append(" ").append(nightTemp).append("°C\n");
            result.append("💨 风向：").append(dayWind).append(" ").append(dayPower).append("级\n");
            result.append("━━━━━━━━━━━━━━━━━━━━");
            
            return result.toString();
            
        } catch (Exception e) {
            log.warn("[WeatherTool] 高德天气响应解析失败: {}", e.getMessage());
            return "⚠️ 天气数据解析失败";
        }
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
