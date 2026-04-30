package com.example.smartassistant.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 位置查询工具
 *
 * <p>使用免费的 ip-api.com 服务获取当前所在位置信息
 * ip-api.com 是一个免费的 IP 地理位置查询服务，支持 HTTP 和 HTTPS
 *
 * <p>使用示例：
 * - 获取当前位置：getCurrentLocation()
 * - 获取当前位置并查询天气：getCurrentLocationWithWeather()
 * - 获取当前位置并规划出行：getCurrentLocationWithTravelPlan()
 */
@Component
public class LocationTool {

    private static final Logger log = LoggerFactory.getLogger(LocationTool.class);

    @Value("${spring.amap.api.key:}")
    private String amapApiKey;

    private final WeatherTool weatherTool;
    private final TravelPlannerTool travelPlannerTool;

    public LocationTool(WeatherTool weatherTool, TravelPlannerTool travelPlannerTool) {
        this.weatherTool = weatherTool;
        this.travelPlannerTool = travelPlannerTool;
    }

    /**
     * 获取当前所在城市名称（仅返回城市名，用于其他工具调用）
     *
     * @return 城市名称
     */
    @Tool(description = "仅返回当前所在城市的名称，不包含其他信息，用于传递给其他需要城市参数的工具")
    public String getCurrentCityName() {
        long startTime = System.currentTimeMillis();
        log.info("[LocationTool] 开始查询城市名称");
        
        try {
            String url = "http://ip-api.com/json/?lang=zh-CN";

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String city = extractCityFromResponse(response.body());
                long endTime = System.currentTimeMillis();
                log.info("[LocationTool] 查询成功，城市: {}, 耗时: {} ms", city, endTime - startTime);
                return city;
            } else {
                long endTime = System.currentTimeMillis();
                log.warn("[LocationTool] 查询失败，状态码: {}, 耗时: {} ms", response.statusCode(), endTime - startTime);
                return "北京市"; // 默认值
            }
        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            log.error("[LocationTool] 查询异常，耗时: {} ms, 错误: {}", endTime - startTime, e.getMessage());
            return "北京市"; // 错误时返回默认值
        }
    }

    /**
     * 获取当前用户的精确坐标（经纬度）
     * 优先使用高德地图 IP 定位 API（精度更高），失败则使用 ip-api.com
     *
     * @return 坐标信息，格式："纬度,经度"
     */
    @Tool(description = "获取当前用户的精确经纬度坐标，用于精准定位和周边搜索，返回格式：纬度,经度")
    public String getCurrentCoordinates() {
        long startTime = System.currentTimeMillis();
        log.info("[LocationTool] 开始查询精确坐标");
        
        // 尝试使用高德地图 IP 定位 API
        String coordinates = getCoordinatesFromAmap();
        if (coordinates != null && !coordinates.isEmpty()) {
            long endTime = System.currentTimeMillis();
            
            // 计算与北京中心的距离
            String[] coords = coordinates.split(",");
            if (coords.length == 2) {
                double lat = Double.parseDouble(coords[0].trim());
                double lon = Double.parseDouble(coords[1].trim());
                double beijingLat = 39.9042;
                double beijingLon = 116.4074;
                double latDiff = Math.abs(lat - beijingLat);
                double lonDiff = Math.abs(lon - beijingLon);
                double distanceKm = Math.sqrt(latDiff * latDiff + lonDiff * lonDiff) * 111;
                
                log.info("[LocationTool] 高德 API 查询成功，坐标: {}, 耗时: {} ms", coordinates, endTime - startTime);
                log.info("[LocationTool] 与北京中心({},{}) 的距离: 纬度差 {}, 经度差 {}, 约 {} 公里",
                        beijingLat, beijingLon, latDiff, lonDiff, distanceKm);
            } else {
                log.info("[LocationTool] 高德 API 查询成功，坐标: {}, 耗时: {} ms", coordinates, endTime - startTime);
            }
            
            return coordinates;
        }
        
        // 降级到 ip-api.com
        log.warn("[LocationTool] 高德 API 失败，降级到 ip-api.com");
        coordinates = getCoordinatesFromIpApi();
        if (coordinates != null && !coordinates.isEmpty()) {
            long endTime = System.currentTimeMillis();
            log.info("[LocationTool] ip-api.com 查询成功，坐标: {}, 耗时: {} ms", coordinates, endTime - startTime);
            return coordinates;
        }
        
        // 都失败，返回默认坐标
        long endTime = System.currentTimeMillis();
        log.warn("[LocationTool] 所有 API 均失败，使用默认坐标，耗时: {} ms", endTime - startTime);
        return "39.9042,116.4074"; // 北京默认坐标
    }

    /**
     * 使用高德地图 IP 定位 API 获取坐标
     */
    private String getCoordinatesFromAmap() {
        try {
            String url = String.format(
                "https://restapi.amap.com/v3/ip?key=%s",
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
                String rectangle = extractJsonValue(body, "\"rectangle\":\"");
                
                if (rectangle.contains(";")) {
                    // 高德返回的是矩形区域：左下角经度,纬度;右上角经度,纬度
                    String[] parts = rectangle.split(";");
                    if (parts.length == 2) {
                        String[] bottomLeft = parts[0].split(",");
                        String[] topRight = parts[1].split(",");
                        
                        if (bottomLeft.length == 2 && topRight.length == 2) {
                            // 计算中心点
                            double lon1 = Double.parseDouble(bottomLeft[0]);
                            double lat1 = Double.parseDouble(bottomLeft[1]);
                            double lon2 = Double.parseDouble(topRight[0]);
                            double lat2 = Double.parseDouble(topRight[1]);
                            
                            double centerLat = (lat1 + lat2) / 2;
                            double centerLon = (lon1 + lon2) / 2;
                            
                            return String.format("%.6f,%.6f", centerLat, centerLon);
                        }
                    }
                }
                
                // 如果没有 rectangle，尝试使用 location
                String location = extractJsonValue(body, "\"location\":\"");
                if (location.contains(",")) {
                    String[] coords = location.split(",");
                    if (coords.length == 2) {
                        // 高德返回：经度,纬度，需要转换为纬度,经度
                        return coords[1] + "," + coords[0];
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[LocationTool] 高德 IP 定位失败: {}", e.getMessage());
        }
        
        return null;
    }

    /**
     * 使用 ip-api.com 获取坐标
     */
    private String getCoordinatesFromIpApi() {
        try {
            String url = "http://ip-api.com/json/?lang=zh-CN";

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String lat = extractJsonValue(response.body(), "\"lat\":");
                String lon = extractJsonValue(response.body(), "\"lon\":");
                
                if (!lat.isEmpty() && !lon.isEmpty()) {
                    return lat + "," + lon;
                }
            }
        } catch (Exception e) {
            log.warn("[LocationTool] ip-api.com 查询失败: {}", e.getMessage());
        }
        
        return null;
    }

    /**
     * 获取当前所在位置信息并查询天气
     * 自动先获取位置，然后查询该位置的天气
     *
     * @return 位置信息和天气信息的组合
     */
    @Tool(description = "获取当前所在城市并查询该城市的天气信息，返回位置和天气的综合信息")
    public String getCurrentLocationWithWeather() {
        // 第一步：获取城市名称
        String city = getCurrentCityName();
        
        // 第二步：使用 WeatherTool 查询天气
        String weatherInfo = weatherTool.query(city);
        
        // 第三步：组合位置信息和天气信息（简洁格式）

        return "【" + city + "】天气信息\n" +
                weatherInfo;
    }

    /**
     * 获取当前位置并根据天气情况规划出行安排
     * 自动获取位置、查询天气、生成出行建议
     *
     * @return 位置信息、天气状况和出行规划的完整建议
     */
    @Tool(description = "获取当前所在城市，查询天气情况，并根据天气智能推荐合适的娱乐项目和出行建议")
    public String getCurrentLocationWithTravelPlan() {
        // 第一步：获取城市名称
        String city = getCurrentCityName();
        
        // 第二步：查询天气
        String weatherInfo = weatherTool.query(city);
        
        // 第三步：根据天气规划出行

        // 第四步：组合所有信息（简洁格式）
        return travelPlannerTool.planTravel(weatherInfo, city);
    }

    /**
     * 获取当前所在位置信息
     * 通过查询请求者的 IP 地址来确定所在城市
     *
     * @return 当前位置信息，包括城市、省份、国家等
     */
    @Tool(description = "获取当前所在城市的地理位置信息，返回城市名称、省份、国家等详细信息")
    public String getCurrentLocation() {
        long startTime = System.currentTimeMillis();
        log.info("[LocationTool] 开始查询位置信息");
        
        try {
            // 使用 ip-api.com 免费 API（HTTP 版本，QPS 限制为 45）
            String url = "http://ip-api.com/json/?lang=zh-CN";

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String result = parseLocationResponse(response.body());
                long endTime = System.currentTimeMillis();
                log.info("[LocationTool] 查询成功，耗时: {} ms", endTime - startTime);
                return result;
            } else {
                long endTime = System.currentTimeMillis();
                log.warn("[LocationTool] 查询失败，状态码: {}, 耗时: {} ms", response.statusCode(), endTime - startTime);
                return "位置查询服务暂时不可用，请稍后重试。";
            }
        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            log.error("[LocationTool] 查询异常，耗时: {} ms, 错误: {}", endTime - startTime, e.getMessage());
            return "位置查询失败：" + e.getMessage();
        }
    }

    /**
     * 从 JSON 响应中提取城市名称
     */
    private String extractCityFromResponse(String jsonResponse) {
        return extractJsonValue(jsonResponse, "\"city\":\"");
    }

    /**
     * 解析位置 API 返回的 JSON 数据
     *
     * @param jsonResponse ip-api.com 返回的 JSON 响应
     * @return 格式化后的位置信息
     */
    private String parseLocationResponse(String jsonResponse) {
        try {
            // 提取关键信息
            String status = extractJsonValue(jsonResponse, "\"status\":\"");
            if (!"success".equals(status)) {
                String message = extractJsonValue(jsonResponse, "\"message\":\"");
                return "位置查询失败：" + (message.isEmpty() ? "未知错误" : message);
            }

            String city = extractJsonValue(jsonResponse, "\"city\":\"");
            String region = extractJsonValue(jsonResponse, "\"regionName\":\"");
            String country = extractJsonValue(jsonResponse, "\"country\":\"");
            String isp = extractJsonValue(jsonResponse, "\"isp\":\"");
            String lat = extractJsonValue(jsonResponse, "\"lat\":");
            String lon = extractJsonValue(jsonResponse, "\"lon\":");
            String timezone = extractJsonValue(jsonResponse, "\"timezone\":\"");
            String query = extractJsonValue(jsonResponse, "\"query\":\"");

            // 构建返回信息（简洁格式，不使用 emoji 和分隔线）
            StringBuilder result = new StringBuilder();
            
            if (!city.isEmpty()) {
                result.append("城市：").append(city).append("\n");
            }
            if (!region.isEmpty()) {
                result.append("省份/州：").append(region).append("\n");
            }
            if (!country.isEmpty()) {
                result.append("国家：").append(country).append("\n");
            }
            if (!isp.isEmpty()) {
                result.append("ISP: ").append(isp).append("\n");
            }
            if (!lat.isEmpty() && !lon.isEmpty()) {
                result.append("坐标：").append(lat).append(", ").append(lon).append("\n");
            }
            if (!timezone.isEmpty()) {
                result.append("时区：").append(timezone).append("\n");
            }
            if (!query.isEmpty()) {
                result.append("IP 地址：").append(query).append("\n");
            }

            return result.toString().trim();
        } catch (Exception e) {
            // 解析失败时返回模拟数据
            return formatSimulatedLocation();
        }
    }

    /**
     * 从 JSON 字符串中提取指定键的值
     */
    private String extractJsonValue(String json, String key) {
        int index = json.indexOf(key);
        if (index == -1) return "";

        int start = index + key.length();
        
        // 处理数值类型（没有引号）
        if (key.contains("\"lat\":") || key.contains("\"lon\":")) {
            int end = Math.min(
                json.indexOf(",", start) != -1 ? json.indexOf(",", start) : json.length(),
                json.indexOf("}", start) != -1 ? json.indexOf("}", start) : json.length()
            );
            return json.substring(start, end).trim();
        }
        
        // 处理字符串类型（有引号）
        int end = json.indexOf("\"", start);
        if (end == -1) return "";

        return json.substring(start, end);
    }

    /**
     * 格式化模拟位置数据（当 API 不可用时的备用方案）
     */
    private String formatSimulatedLocation() {
        return """
                📍 位置信息
                ━━━━━━━━━━━━━━━━━━━━
                🏙️  城市：北京市
                🗺️  省份/州：北京市
                🌏  国家：中国
                📡  坐标：39.9042, 116.4074
                ⏰  时区：Asia/Shanghai
                ━━━━━━━━━━━━━━━━━━━━
                ⚠️ 注意：当前返回模拟数据，真实 API 暂时不可用""";
    }
}
