package com.example.smartassistant.service.data;

import com.example.smartassistant.entity.TouristAttraction;
import com.example.smartassistant.entity.TravelNote;
import com.example.smartassistant.mapper.TouristAttractionMapper;
import com.example.smartassistant.mapper.TravelNoteMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 高德地图 POI 层级导入服务。
 * 导入知名景点及其子项目到 tourist_attractions，同时生成游记。
 */
@Service
public class AmapNoteImportService {

    private static final Logger log = LoggerFactory.getLogger(AmapNoteImportService.class);
    private static final String AMAP_URL = "https://restapi.amap.com/v3/place/text";

    private final TouristAttractionMapper attractionMapper;
    private final TravelNoteMapper travelNoteMapper;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    private static final Map<String, String> CITY_PROVINCE = new HashMap<>();
    static {
        CITY_PROVINCE.put("北京", "北京"); CITY_PROVINCE.put("上海", "上海");
        CITY_PROVINCE.put("广州", "广东"); CITY_PROVINCE.put("深圳", "广东");
        CITY_PROVINCE.put("杭州", "浙江"); CITY_PROVINCE.put("成都", "四川");
        CITY_PROVINCE.put("西安", "陕西"); CITY_PROVINCE.put("重庆", "重庆");
        CITY_PROVINCE.put("南京", "江苏"); CITY_PROVINCE.put("武汉", "湖北");
        CITY_PROVINCE.put("苏州", "江苏"); CITY_PROVINCE.put("厦门", "福建");
        CITY_PROVINCE.put("长沙", "湖南"); CITY_PROVINCE.put("青岛", "山东");
        CITY_PROVINCE.put("三亚", "海南"); CITY_PROVINCE.put("昆明", "云南");
        CITY_PROVINCE.put("丽江", "云南"); CITY_PROVINCE.put("桂林", "广西");
        CITY_PROVINCE.put("哈尔滨", "黑龙江"); CITY_PROVINCE.put("大连", "辽宁");
    }

    public AmapNoteImportService(TouristAttractionMapper attractionMapper,
                                  TravelNoteMapper travelNoteMapper,
                                  @Value("${amap.api.key}") String apiKey) {
        this.attractionMapper = attractionMapper;
        this.travelNoteMapper = travelNoteMapper;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 导入指定城市的景点层级数据。
     */
    public Map<String, Object> importCity(String city) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("city", city);
        log.info("[AmapNote] 开始导入: {}", city);
        try {
            // 1. 搜索该城市的知名景点
            List<JsonNode> parents = fetchMajorSpots(city);
            result.put("spots_found", parents.size());

            int totalChildren = 0;
            int totalNotes = 0;
            StringBuilder notesContent = new StringBuilder();
            notesContent.append(city).append("旅游攻略\n\n");

            for (JsonNode parent : parents) {
                String parentName = parent.get("name").asText("");
                String parentId = parent.get("id").asText("");
                String parentRating = parent.at("/biz_ext/rating").asText("");
                String parentLevel = parent.at("/biz_ext/level").asText("");

                // 2. 获取子景点（游玩项目）
                List<JsonNode> children = fetchChildren(parentId, city);
                totalChildren += children.size();

                // 3. 生成该景点在 tourist_attractions 中的主记录
                TouristAttraction mainAttr = new TouristAttraction();
                mainAttr.setName(parentName);
                mainAttr.setCity(city);
                mainAttr.setProvince(CITY_PROVINCE.getOrDefault(city, "未知"));
                mainAttr.setLevel(parentLevel.isEmpty() ? "未评级" : parentLevel);
                mainAttr.setOpenTime("请查询景区公告");
                mainAttr.setSuggestDuration(120);
                if (!parentRating.isEmpty()) {
                    mainAttr.setDescription("评分" + parentRating + "，共" + children.size() + "个子景点/游玩项目");
                } else {
                    mainAttr.setDescription("共" + children.size() + "个子景点/游玩项目");
                }
                try { attractionMapper.insert(mainAttr); } catch (Exception e) {
                    log.warn("[AmapNote] 插入主景点失败: {}", e.getMessage());
                }

                // 4. 写入子景点到 tourist_attractions
                notesContent.append("【").append(parentName).append("】");
                if (!parentRating.isEmpty()) notesContent.append("（评分").append(parentRating).append("）");
                notesContent.append("\n");
                for (JsonNode child : children) {
                    String childName = child.get("name").asText("");
                    String childType = child.has("type") ? child.get("type").asText("") : "";
                    String childRating = child.at("/biz_ext/rating").asText("");
                    String childOpen = child.at("/biz_ext/opentime2").asText("");
                    String childAddr = child.has("address") ? child.get("address").asText("") : "";

                    TouristAttraction attr = new TouristAttraction();
                    attr.setName(childName);
                    attr.setCity(city);
                    attr.setProvince(CITY_PROVINCE.getOrDefault(city, "未知"));
                    attr.setLevel("未评级");
                    attr.setDescription("属于" + parentName + (childRating.isEmpty() ? "" : "，评分" + childRating));
                    attr.setOpenTime(childOpen.isEmpty() ? "请查询景区公告" : childOpen);
                    attr.setSuggestDuration(60);
                    if (!childAddr.isEmpty()) attr.setDescription(attr.getDescription() + "，" + childAddr);
                    try { attractionMapper.insert(attr); } catch (Exception e) {
                        log.warn("[AmapNote] 插入子景点失败: {}", e.getMessage());
                    }

                    notesContent.append("  • ").append(childName);
                    if (!childRating.isEmpty()) notesContent.append("（评分").append(childRating).append("）");
                    if (!childOpen.isEmpty()) notesContent.append("  ").append(childOpen);
                    notesContent.append("\n");
                }
                notesContent.append("\n");
            }

            // 5. 生成游记
            if (notesContent.length() > 50) {
                String tags = city + ",旅游,攻略,景点";
                TravelNote note = TravelNote.builder()
                        .userId(1L).title(city + "旅游攻略——热门景点与游玩项目")
                        .content(notesContent.toString().trim())
                        .sourceType("amap").location(city).tags(tags).status("active").build();
                travelNoteMapper.insert(note);
                totalNotes = 1;
                result.put("note_id", note.getId());
                log.info("[AmapNote] 游记已创建: id={}", note.getId());
            }

            result.put("children_imported", totalChildren);
            result.put("notes_created", totalNotes);
            result.put("success", true);
            log.info("[AmapNote] 导入完成: city={}, spots={}, children={}", city, parents.size(), totalChildren);

        } catch (Exception e) {
            log.error("[AmapNote] 导入失败: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ==================== 高德 API 调用 ====================

    /**
     * 搜索城市的知名景点（parent POI）。
     */
    private List<JsonNode> fetchMajorSpots(String city) throws Exception {
        Set<String> seen = new HashSet<>();
        List<JsonNode> spots = new ArrayList<>();
        // 用多种关键词提高覆盖
        String[] keywords = {city + "风景区", city + "景点", city + "公园"};
        for (String kw : keywords) {
            String url = AMAP_URL + "?keywords=" + URLEncoder.encode(kw, StandardCharsets.UTF_8)
                    + "&city=" + URLEncoder.encode(city, StandardCharsets.UTF_8)
                    + "&types=110000&output=json&key=" + apiKey
                    + "&pagesize=15&page=1&offset=0";
            var req = HttpRequest.newBuilder(URI.create(url)).GET().timeout(java.time.Duration.ofSeconds(10)).build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) continue;
            JsonNode root = objectMapper.readTree(resp.body());
            if (!"1".equals(root.get("status").asText())) continue;
            JsonNode pois = root.get("pois");
            if (pois == null || !pois.isArray()) continue;
            for (JsonNode poi : pois) {
                String id = poi.get("id").asText("");
                if (id.isEmpty() || seen.contains(id)) continue;
                // 只保留有子节点的景区类型
                String type = poi.has("type") ? poi.get("type").asText("") : "";
                if (type.contains("风景名胜") || type.contains("公园") || type.contains("景点")) {
                    seen.add(id);
                    spots.add(poi);
                }
            }
        }
        // 按评分排序（高到低）
        spots.sort((a, b) -> {
            double ra = a.at("/biz_ext/rating").asDouble(0);
            double rb = b.at("/biz_ext/rating").asDouble(0);
            return Double.compare(rb, ra);
        });
        // 取 Top 10
        return spots.subList(0, Math.min(10, spots.size()));
    }

    /**
     * 查询某景点的子景点/游玩项目。
     * 使用父景点名称作为关键词搜索，然后筛选 parent 字段匹配的 POI。
     */
    private List<JsonNode> fetchChildren(String parentId, String city) throws Exception {
        // 先通过父景点 ID 获取父景点名称
        String detailUrl = "https://restapi.amap.com/v3/place/detail?id=" + parentId + "&output=json&key=" + apiKey;
        var detailReq = HttpRequest.newBuilder(URI.create(detailUrl)).GET().timeout(java.time.Duration.ofSeconds(5)).build();
        var detailResp = httpClient.send(detailReq, HttpResponse.BodyHandlers.ofString());
        String parentName = "";
        if (detailResp.statusCode() == 200) {
            JsonNode detailRoot = objectMapper.readTree(detailResp.body());
            JsonNode pois = detailRoot.get("pois");
            if (pois != null && pois.isArray() && pois.size() > 0) {
                parentName = pois.get(0).get("name").asText("");
            }
        }
        if (parentName.isEmpty()) return List.of();

        // 用父景点名称搜索，获取包含父景点和子景点的结果
        Set<String> seen = new HashSet<>();
        List<JsonNode> all = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            String url = AMAP_URL + "?keywords=" + URLEncoder.encode(parentName, StandardCharsets.UTF_8)
                    + "&city=" + URLEncoder.encode(city, StandardCharsets.UTF_8)
                    + "&output=json&key=" + apiKey + "&pagesize=20&page=" + page + "&offset=0";
            var req = HttpRequest.newBuilder(URI.create(url)).GET().timeout(java.time.Duration.ofSeconds(10)).build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) break;
            JsonNode root = objectMapper.readTree(resp.body());
            if (!"1".equals(root.get("status").asText())) break;
            JsonNode pois = root.get("pois");
            if (pois == null || !pois.isArray() || pois.size() == 0) break;
            for (JsonNode poi : pois) {
                String id = poi.get("id").asText("");
                if (!id.isEmpty() && !seen.contains(id)) {
                    seen.add(id);
                    all.add(poi);
                }
            }
            if (pois.size() < 20) break;
        }

        // 筛选出 parent=parentId 的 POI（排除父景点本身）
        List<JsonNode> children = new ArrayList<>();
        for (JsonNode poi : all) {
            String p = poi.has("parent") ? poi.get("parent").asText("") : "";
            String id = poi.get("id").asText("");
            if (p.equals(parentId) && !id.equals(parentId)) {
                children.add(poi);
            }
        }
        log.info("[AmapNote] 景点 {} 的子项目: {}", parentName, children.size());
        return children;
    }
}
