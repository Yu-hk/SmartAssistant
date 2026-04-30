package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * LLM 智能偏好提取服务
 * 使用 DeepSeek 模型从用户问题中提取结构化偏好信息
 * <p>
 * ⭐ 使用中文分词器增强降级方案的提取能力
 */
@Service
public class LLMPreferenceExtractor {
    
    private static final Logger log = LoggerFactory.getLogger(LLMPreferenceExtractor.class);
    
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final ChineseTokenizer tokenizer;
    
    public LLMPreferenceExtractor(ChatClient.Builder chatClientBuilder, 
                                  ChineseTokenizer tokenizer) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = new ObjectMapper();
        this.tokenizer = tokenizer;
    }
    
    /**
     * 从用户问题中提取结构化偏好
     * 
     * @param question 用户问题
     * @return 提取的偏好信息（JSON 格式）
     */
    public ExtractedPreferences extract(String question) {
        if (question == null || question.isBlank()) {
            return new ExtractedPreferences();
        }
        
        try {
            String prompt = buildExtractionPrompt(question);
            
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            
            log.debug("[LLM提取] 原始响应: {}", response);
            
            // 解析 JSON 响应
            return parseResponse(response);
            
        } catch (Exception e) {
            log.error("[LLM提取] 提取失败，降级到正则提取: {}", e.getMessage());
            // 降级到基于规则的提取
            return fallbackExtraction(question);
        }
    }
    
    /**
     * 构建提取 Prompt
     * <p>
     * 【重要定义】
     * - 用户画像(user_profile)：仅包含用户表达情绪/偏好的关键词
     * - 触发条件：必须出现"喜欢"、"经常"、"讨厌"、"厌烦"、"偏好"、"倾向"等情绪性表述
     * - 其他普通信息（如地点、时间、目的）按正常字段提取，不计入用户画像
     */
    private String buildExtractionPrompt(String question) {
        return """
                你是一个专业的用户偏好分析助手。请从用户的问题中提取结构化信息。
                
                【核心定义：用户画像】
                用户画像(user_profile) 用于构建用户偏好向量，只提取满足以下条件的关键词：
                
                ⭐ 触发条件：用户使用了情绪性表述
                - "喜欢" / "爱" / "偏好" / "倾向于"  →  提取后面的正面偏好
                - "经常" / "总爱" / "习惯"          →  提取习惯性偏好
                - "讨厌" / "厌烦" / "不喜欢" / "厌恶"  →  提取负面偏好（需标注为负向）
                - "不要" / "拒绝" / "排斥"          →  提取排斥项（需标注为负向）
                
                【普通信息字段】（不计入用户画像）
                - location: 地点名称（城市、省份、景区等），如果没有则留空
                - purpose: 目的类型，food/ travel/ weather/ other
                - time: 时间信息，如 "周末"、"明天"，如果未提及则为 null
                
                【重要规则】
                1. 只有情绪性表述触发的偏好才进入 user_profile
                2. 普通描述（如"推荐川菜"）不提取为用户画像
                3. user_profile 中的负面偏好需标注"[负向]"标记
                4. 同一类型偏好有多项时全部列出
                
                【用户问题】
                %s
                
                【输出格式示例】
                
                例1: "我喜欢吃辣，经常去川菜馆"
                {
                  "location": null,
                  "purpose": "food",
                  "time": null,
                  "user_profile": {
                    "positive": ["辣", "川菜"],
                    "negative": []
                  }
                }
                
                例2: "讨厌香菜，不要麻婆豆腐"
                {
                  "location": null,
                  "purpose": "food",
                  "time": null,
                  "user_profile": {
                    "positive": [],
                    "negative": ["香菜", "麻婆豆腐"]
                  }
                }
                
                例3: "北京有哪些川菜馆？"
                {
                  "location": "北京",
                  "purpose": "food",
                  "time": null,
                  "user_profile": {
                    "positive": [],
                    "negative": []
                  }
                }
                
                例4: "周末去杭州玩，想吃清淡的"
                {
                  "location": "杭州",
                  "purpose": "travel",
                  "time": "周末",
                  "user_profile": {
                    "positive": ["清淡"],
                    "negative": []
                  }
                }
                
                【最终输出】
                仅输出 JSON，不要包含 markdown 标记。
                """.formatted(question);
    }
    
    /**
     * 解析 LLM 响应（支持新的 user_profile 格式）
     * <p>
     * 新格式：user_profile 包含 positive 和 negative 两个数组
     */
    private ExtractedPreferences parseResponse(String response) {
        try {
            // 清理可能的 markdown 标记
            String cleaned = response.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").trim();
            
            JsonNode json = objectMapper.readTree(cleaned);
            
            ExtractedPreferences prefs = new ExtractedPreferences();
            
            // 提取地点
            if (json.has("location") && !json.get("location").isNull()) {
                prefs.setLocation(json.get("location").asText());
            }
            
            // 提取目的
            if (json.has("purpose") && !json.get("purpose").isNull()) {
                prefs.setPurpose(json.get("purpose").asText());
            }
            
            // 提取时间
            if (json.has("time") && !json.get("time").isNull()) {
                prefs.setTime(json.get("time").asText());
            }
            
            // 提取用户画像（positive 和 negative）
            if (json.has("user_profile") && json.get("user_profile").isObject()) {
                JsonNode profile = json.get("user_profile");
                
                // 正面偏好
                if (profile.has("positive") && profile.get("positive").isArray()) {
                    profile.get("positive").forEach(node -> {
                        categorizePreference(prefs, node.asText());
                    });
                }
                
                // 负面偏好
                if (profile.has("negative") && profile.get("negative").isArray()) {
                    profile.get("negative").forEach(node -> {
                        prefs.getDietaryRestrictions().add("不喜欢:" + node.asText());
                    });
                }
            }
            
            // 兼容旧格式（如果 LLM 仍然返回 food_preferences 等字段）
            if (json.has("food_preferences") && json.get("food_preferences").isArray()) {
                json.get("food_preferences").forEach(node -> {
                    categorizePreference(prefs, node.asText());
                });
            }
            
            if (json.has("travel_preferences") && json.get("travel_preferences").isArray()) {
                json.get("travel_preferences").forEach(node -> {
                    prefs.getTravelPreferences().add(node.asText());
                });
            }
            
            if (json.has("budget") && !json.get("budget").isNull()) {
                prefs.setBudget(json.get("budget").asText());
            }
            
            if (json.has("dietary_restrictions") && json.get("dietary_restrictions").isArray()) {
                json.get("dietary_restrictions").forEach(node -> {
                    prefs.getDietaryRestrictions().add(node.asText());
                });
            }
            
            log.info("[LLM提取] 成功: location={}, purpose={}, user_profile={}, dislikes={}",
                    prefs.getLocation(), prefs.getPurpose(), 
                    prefs.getFoodPreferences(), prefs.getDietaryRestrictions());
            
            return prefs;
            
        } catch (Exception e) {
            log.error("[LLM提取] JSON 解析失败: {}", e.getMessage());
            throw new RuntimeException("JSON 解析失败", e);
        }
    }
    
    /**
     * 将偏好关键词分类到 food 或 travel
     */
    private void categorizePreference(ExtractedPreferences prefs, String pref) {
        // 美食相关关键词
        Set<String> foodKeywords = Set.of(
            "辣", "麻辣", "香辣", "清淡", "甜", "甜品", "酸", "苦", "咸",
            "川菜", "粤菜", "湘菜", "鲁菜", "浙菜", "苏菜", "闽菜", "徽菜", "京菜",
            "火锅", "烧烤", "烤肉", "海鲜", "日料", "韩餐", "西餐", "自助餐",
            "素食", "斋", "面食", "粥", "小吃", "点心", "甜点", "饮品",
            "油炸", "煎", "蒸", "煮", "炖", "烤", "清淡"
        );
        
        // 旅行相关关键词
        Set<String> travelKeywords = Set.of(
            "自然", "山水", "风景", "海岛", "沙滩", "森林", "草原", "沙漠",
            "文化", "历史", "古迹", "博物馆", "寺庙", "古镇", "老街",
            "休闲", "放松", "度假", "慢节奏", "SPA", "温泉",
            "户外", "徒步", "登山", "滑雪", "潜水", "冲浪", "骑行",
            "亲子", "家庭", "带孩子", "儿童", "乐园",
            "购物", "夜市", "集市", "商圈"
        );
        
        // 检测是否为美食相关
        for (String food : foodKeywords) {
            if (pref.contains(food) || food.contains(pref)) {
                prefs.getFoodPreferences().add(pref);
                return;
            }
        }
        
        // 检测是否为旅行相关
        for (String travel : travelKeywords) {
            if (pref.contains(travel) || travel.contains(pref)) {
                prefs.getTravelPreferences().add(pref);
                return;
            }
        }
        
        // 默认归类为美食偏好
        prefs.getFoodPreferences().add(pref);
    }
    
    /**
     * 降级方案：基于规则的提取
     * <p>
     * ⭐ 只有检测到情绪性表述时，才提取用户画像
     */
    private ExtractedPreferences fallbackExtraction(String question) {
        log.info("[LLM提取] 使用降级方案（分词器增强提取）");
        
        ExtractedPreferences prefs = new ExtractedPreferences();
        
        // 检测是否有情绪性表述
        Set<String> emotionalKeywords = Set.of(
            "喜欢", "爱", "偏好", "倾向", "经常", "总爱", "习惯",
            "讨厌", "厌烦", "不喜欢", "厌恶", "不要", "拒绝", "排斥"
        );
        boolean hasEmotional = tokenizer.containsAnyKeyword(question, emotionalKeywords);
        
        // 只有在有情绪性表述时才提取用户画像
        if (hasEmotional) {
            log.info("[LLM提取] 检测到情绪性表述，提取用户画像");
            
            // ⭐ 正面偏好提取（基于情绪性关键词）
            if (tokenizer.containsAnyKeyword(question, Set.of("辣", "麻辣", "香辣"))) {
                prefs.getFoodPreferences().add("辣");
            }
            if (tokenizer.containsAnyKeyword(question, Set.of("清淡", "清香"))) {
                prefs.getFoodPreferences().add("清淡");
            }
            if (tokenizer.containsAnyKeyword(question, Set.of("甜", "甜品", "甜点"))) {
                prefs.getFoodPreferences().add("甜");
            }
            if (tokenizer.containsAnyKeyword(question, Set.of("川菜", "粤菜", "湘菜"))) {
                prefs.getFoodPreferences().add("川菜");
            }
            
            // ⭐ 负面偏好提取
            if (tokenizer.containsAnyKeyword(question, Set.of("讨厌", "厌烦", "不喜欢", "厌恶"))) {
                // 尝试提取被讨厌的内容
                if (tokenizer.containsAnyKeyword(question, Set.of("香菜"))) {
                    prefs.getDietaryRestrictions().add("不喜欢:香菜");
                }
                if (tokenizer.containsAnyKeyword(question, Set.of("辣", "麻辣"))) {
                    prefs.getDietaryRestrictions().add("不喜欢:辣");
                }
            }
            
            // ⭐ 旅行偏好提取
            if (tokenizer.containsAnyKeyword(question, Set.of("自然", "山水", "风景"))) {
                prefs.getTravelPreferences().add("自然");
            }
            if (tokenizer.containsAnyKeyword(question, Set.of("文化", "历史", "古迹"))) {
                prefs.getTravelPreferences().add("文化");
            }
            if (tokenizer.containsAnyKeyword(question, Set.of("休闲", "放松", "度假"))) {
                prefs.getTravelPreferences().add("休闲");
            }
        } else {
            log.info("[LLM提取] 无情绪性表述，跳过用户画像提取");
        }
        
        // 通用信息提取（不受情绪性表述限制）
        // 预算提取
        if (tokenizer.containsAnyKeyword(question, Set.of("便宜", "经济", "实惠", "相因"))) {
            prefs.setBudget("low");
        } else if (tokenizer.containsAnyKeyword(question, Set.of("高端", "豪华", "奢侈"))) {
            prefs.setBudget("high");
        } else if (tokenizer.containsAnyKeyword(question, Set.of("人均", "性价比"))) {
            prefs.setBudget("medium");
        }
        
        // 饮食限制提取（明确限制，非偏好）
        if (tokenizer.containsAnyKeyword(question, Set.of("素食", "斋"))) {
            prefs.getDietaryRestrictions().add("素食");
        }
        if (tokenizer.containsAnyKeyword(question, Set.of("清真"))) {
            prefs.getDietaryRestrictions().add("清真");
        }
        if (tokenizer.containsAnyKeyword(question, Set.of("无辣", "不辣", "少辣"))) {
            prefs.getDietaryRestrictions().add("少辣");
        }
        
        return prefs;
    }
    
    /**
     * 提取的偏好数据结构
     */
    @Setter
    @Getter
    public static class ExtractedPreferences {
        // Getters and Setters
        private String location;
        private String purpose;
        private List<String> foodPreferences = new ArrayList<>();
        private List<String> travelPreferences = new ArrayList<>();
        private String budget;
        private List<String> dietaryRestrictions = new ArrayList<>();
        private String time;

    }
}
