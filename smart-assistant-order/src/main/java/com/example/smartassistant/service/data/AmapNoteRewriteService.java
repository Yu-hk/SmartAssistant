package com.example.smartassistant.service.data;

import com.example.smartassistant.common.config.DeepSeekApiClient;
import com.example.smartassistant.entity.TravelNote;
import com.example.smartassistant.mapper.TravelNoteMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * 高德 POI → LLM 重写 → 游记入库服务。
 * 从高德获取景点/美食 POI，用 DeepSeek 重写为叙事风格游记，存入 user_travel_notes。
 */
@Service
public class AmapNoteRewriteService {

    private static final Logger log = LoggerFactory.getLogger(AmapNoteRewriteService.class);
    private static final String AMAP_URL = "https://restapi.amap.com/v3/place/text";
    private static final String[] SCENIC_TYPES = {"110000", "110100", "110200"};
    private static final String[] FOOD_TYPES = {"050000", "050100", "050300"};

    private final TravelNoteMapper travelNoteMapper;
    private final DeepSeekApiClient apiClient;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String amapKey;

    public AmapNoteRewriteService(TravelNoteMapper travelNoteMapper,
                                   @Value("${amap.api.key}") String amapKey,
                                   @Value("${spring.ai.deepseek.api-key}") String deepseekKey) {
        this.travelNoteMapper = travelNoteMapper;
        this.amapKey = amapKey;
        this.apiClient = new DeepSeekApiClient(deepseekKey);
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 导入并重写指定城市的攻略。
     */
    public Map<String, Object> importAndRewrite(String city) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("city", city);
        log.info("[Rewrite] 开始: {}", city);
        try {
            // 1. 获取景点和美食 POI
            List<Map<String, String>> spots = fetchPois(city, SCENIC_TYPES, 20);
            List<Map<String, String>> foods = fetchPois(city, FOOD_TYPES, 15);
            result.put("spots", spots.size());
            result.put("foods", foods.size());

            // 2. LLM 重写
            String rewritten = rewriteWithLLM(city, spots, foods);
            if (rewritten == null || rewritten.isBlank()) {
                result.put("success", false);
                result.put("error", "LLM 返回为空");
                return result;
            }

            // 3. 入库
            String tags = city + ",旅游,攻略";
            if (!spots.isEmpty()) tags += ",景点";
            if (!foods.isEmpty()) tags += ",美食";
            TravelNote note = TravelNote.builder()
                    .userId(1L)
                    .title(city + "旅游攻略——必去景点与地道美食")
                    .content(rewritten)
                    .sourceType("llm_rewrite")
                    .location(city).tags(tags).status("active").build();
            travelNoteMapper.insert(note);
            result.put("note_id", note.getId());
            result.put("content_length", rewritten.length());
            result.put("success", true);
            log.info("[Rewrite] 完成: city={}, noteId={}, len={}", city, note.getId(), rewritten.length());

        } catch (Exception e) {
            log.error("[Rewrite] 失败: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ==================== 高德 POI 获取 ====================

    private List<Map<String, String>> fetchPois(String city, String[] types, int limit) throws Exception {
        Set<String> seen = new HashSet<>();
        List<Map<String, String>> all = new ArrayList<>();
        for (String type : types) {
            if (all.size() >= limit) break;
            String url = AMAP_URL + "?city=" + URLEncoder.encode(city, StandardCharsets.UTF_8)
                    + "&types=" + type + "&output=json&key=" + amapKey
                    + "&pagesize=20&page=1&offset=0";
            var req = HttpRequest.newBuilder(URI.create(url)).GET().timeout(java.time.Duration.ofSeconds(10)).build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) continue;
            JsonNode root = objectMapper.readTree(resp.body());
            if (!"1".equals(root.get("status").asText())) continue;
            JsonNode pois = root.get("pois");
            if (pois == null || !pois.isArray()) continue;
            for (JsonNode poi : pois) {
                String name = poi.get("name").asText("");
                if (name.isEmpty() || seen.contains(name)) continue;
                seen.add(name);
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("type", poi.has("type") ? poi.get("type").asText("") : "");
                m.put("rating", poi.at("/biz_ext/rating").asText(""));
                m.put("cost", poi.at("/biz_ext/cost").asText(""));
                m.put("openTime", poi.at("/biz_ext/opentime2").asText(""));
                m.put("address", poi.has("address") ? poi.get("address").asText("") : "");
                m.put("level", poi.at("/biz_ext/level").asText(""));
                all.add(m);
                if (all.size() >= limit) break;
            }
        }
        // 按评分排序
        all.sort((a, b) -> {
            double ra = parseDoubleSafe(a.get("rating"));
            double rb = parseDoubleSafe(b.get("rating"));
            return Double.compare(rb, ra);
        });
        return all;
    }

    private double parseDoubleSafe(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    // ==================== LLM 重写 ====================

    private String rewriteWithLLM(String city, List<Map<String, String>> spots, List<Map<String, String>> foods) throws Exception {
        // 构建 prompt
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的旅游攻略写手。请根据以下" + city + "的景点和美食数据，");
        prompt.append("写一篇流畅、实用的" + city + "旅游攻略。要求：\n");
        prompt.append("1. 用叙事风格，像一篇真实的游记\n");
        prompt.append("2. 包含路线建议、游玩顺序、用餐推荐\n");
        prompt.append("3. 将评分高的景点和餐厅自然融入行文中\n");
        prompt.append("4. 每段100-300字，适合作为RAG检索片段\n");
        prompt.append("5. 不要列点，用连贯的段落\n");
        prompt.append("6. 总字数800-1500字\n\n");
        prompt.append("以下是" + city + "的景点数据（名称、评分、开放时间）：\n");
        for (Map<String, String> s : spots) {
            prompt.append("- ").append(s.get("name"));
            if (!s.get("rating").isEmpty()) prompt.append("（评分").append(s.get("rating")).append("）");
            if (!s.get("level").isEmpty()) prompt.append("【").append(s.get("level")).append("】");
            if (!s.get("openTime").isEmpty()) prompt.append(" 开放时间：").append(s.get("openTime"));
            prompt.append("\n");
        }
        if (!foods.isEmpty()) {
            prompt.append("\n美食推荐（名称、评分、人均消费）：\n");
            for (Map<String, String> f : foods) {
                prompt.append("- ").append(f.get("name"));
                if (!f.get("rating").isEmpty()) prompt.append("（评分").append(f.get("rating")).append("）");
                if (!f.get("cost").isEmpty()) prompt.append(" 人均约").append(f.get("cost")).append("元");
                prompt.append("\n");
            }
        }

        // 调用 DeepSeek API
        ArrayNode msgs = objectMapper.createArrayNode();
        ObjectNode sysMsg = msgs.addObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", "你是一个专业的旅游攻略写手，擅长将结构化POI数据改写为流畅的游记。");
        ObjectNode userMsg = msgs.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", prompt.toString());

        String json = apiClient.buildRequestJson("deepseek-v4-flash", 0.7, 2048, msgs);
        String respBody = apiClient.sendRequest(json);
        JsonNode root = apiClient.parseResponse(respBody);
        JsonNode content = root.at("/choices/0/message/content");
        return content != null && !content.isNull() ? content.asText().trim() : "";
    }
}
