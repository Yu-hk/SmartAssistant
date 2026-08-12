/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.toolregistry.general.tool;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.gateway.tool.ToolRegistry;
import com.example.smartassistant.common.intent.WeatherQuerySupport;
import com.example.smartassistant.common.tool.ToolResult;
import com.example.smartassistant.common.tool.spi.RegistryTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Weather query tool — queries real-time weather and forecasts for a city.
 */
@Component
public class WeatherTool implements RegistryTool {

    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);
    private static final Pattern COORDINATES = Pattern.compile(
            "^-?\\d{1,2}(?:\\.\\d+)?,\\s*-?\\d{1,3}(?:\\.\\d+)?$");
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WeatherTool() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    @Tool(description = "查询指定地点的实时天气和未来天气预报，包括温度、天气状况、风速等。地点可以是中文或英文城市名，也可以是用户已授权的纬度,经度坐标。")
    public String queryWeather(
            @ToolParam(description = "城市名称或纬度,经度坐标，如'北京'、'London'、'39.9042,116.4074'", required = true) String city) {
        if (city == null || city.isBlank()) {
            return ToolResult.error(AgentErrorCode.WEATHER_NO_DATA, "请提供要查询的城市或位置。");
        }
        String normalizedLocation = WeatherQuerySupport.normalizeLocation(city);
        if (normalizedLocation == null) {
            return ToolResult.error(AgentErrorCode.WEATHER_NO_DATA, "请提供要查询的城市或位置。");
        }
        boolean coordinateQuery = COORDINATES.matcher(normalizedLocation).matches();
        log.info("[WeatherTool] 查询天气: locationType={}", coordinateQuery ? "coordinates" : "city");
        try {
            var req = HttpRequest.newBuilder(buildWeatherUri(normalizedLocation)).GET()
                    .timeout(java.time.Duration.ofSeconds(5))
                    .header("User-Agent", "curl")
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                return ToolResult.error(AgentErrorCode.SERVICE_WEATHER_UNAVAILABLE, "天气查询失败，请检查城市名称是否正确。");
            }

            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode current = root.at("/current_condition/0");
            if (current == null || current.isNull()) {
                return ToolResult.error(AgentErrorCode.WEATHER_NO_DATA, "未找到该城市的天气数据。");
            }

            String temp = current.get("temp_C").asText();
            String feelsLike = current.get("FeelsLikeC").asText();
            String humidity = current.get("humidity").asText();
            String desc = current.at("/weatherDesc/0/value").asText();
            String windSpeed = current.get("windspeedKmph").asText();
            String windDir = current.get("winddir16Point").asText();
            String visibility = current.get("visibility").asText();
            String displayLocation = coordinateQuery ? nearestAreaName(root) : normalizedLocation;

            StringBuilder forecast = new StringBuilder();
            JsonNode forecasts = root.get("weather");
            if (forecasts != null && forecasts.isArray()) {
                for (int i = 0; i < Math.min(3, forecasts.size()); i++) {
                    JsonNode day = forecasts.get(i);
                    String date = day.get("date").asText();
                    String maxTemp = day.get("maxtempC").asText();
                    String minTemp = day.get("mintempC").asText();
                    String hourlyDesc = day.at("/hourly/0/weatherDesc/0/value").asText();
                    forecast.append("\n  ").append(date).append(": ").append(hourlyDesc)
                            .append("，").append(minTemp).append("~").append(maxTemp).append("°C");
                }
            }

            return String.format(
                "📍 %s 当前天气\n🌡️ 温度：%s°C（体感 %s°C）\n☁️ 天气：%s\n💧 湿度：%s%%\n💨 风速：%s %s\n👁️ 能见度：%s km\n\n📅 未来三天预报：%s",
                displayLocation, temp, feelsLike, desc, humidity, windSpeed, windDir, visibility, forecast.toString()
            );

        } catch (Exception e) {
            log.warn("[WeatherTool] 查询失败: {}", e.getMessage());
            return ToolResult.error(AgentErrorCode.SERVICE_WEATHER_UNAVAILABLE, "天气服务暂时不可用", "请稍后重试");
        }
    }

    static URI buildWeatherUri(String location) {
        String encoded = URLEncoder.encode(location.trim(), StandardCharsets.UTF_8);
        return URI.create("https://wttr.in/" + encoded + "?format=j1");
    }

    private static String nearestAreaName(JsonNode root) {
        String area = root.at("/nearest_area/0/areaName/0/value").asText("");
        String region = root.at("/nearest_area/0/region/0/value").asText("");
        if (!area.isBlank() && !region.isBlank() && !area.equalsIgnoreCase(region)) {
            return region + " " + area;
        }
        if (!area.isBlank()) return area;
        if (!region.isBlank()) return region;
        return "当前位置";
    }
}
