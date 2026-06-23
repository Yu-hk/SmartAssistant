package com.example.smartassistant.general.tool;

import com.example.smartassistant.common.error.AgentErrorCode;
import com.example.smartassistant.common.tool.ToolResult;
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

/**
 * 天气查询工具
 */
@Component
public class WeatherTool {

    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WeatherTool() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    @Tool(description = "查询指定城市的实时天气和未来天气预报，包括温度、天气状况、风速等。城市可以是中文或英文名称，如'北京'、'上海'、'London'。")
    public String queryWeather(
            @ToolParam(description = "城市名称，如'北京'、'London'", required = true) String city) {
        log.info("[WeatherTool] 查询天气: {}", city);
        try {
            // 使用 wttr.in（免费，无需 API Key）
            String encoded = URLEncoder.encode(city, StandardCharsets.UTF_8);
            String url = "https://wttr.in/" + encoded + "?format=j1";
            var req = HttpRequest.newBuilder(URI.create(url)).GET()
                    .timeout(java.time.Duration.ofSeconds(10))
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

            // 未来 3 天预报
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
                city, temp, feelsLike, desc, humidity, windSpeed, windDir, visibility, forecast.toString()
            );

        } catch (Exception e) {
            log.warn("[WeatherTool] 查询失败: {}", e.getMessage());
            return ToolResult.error(AgentErrorCode.SERVICE_WEATHER_UNAVAILABLE, "天气服务暂时不可用", "请稍后重试");
        }
    }
}
