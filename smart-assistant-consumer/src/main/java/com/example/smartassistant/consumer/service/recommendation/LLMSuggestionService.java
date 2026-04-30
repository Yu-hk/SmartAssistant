package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * LLM 智能建议生成服务 (Phase 1)
 * <p>
 * 基于 ChatClient 的纯 LLM 方案，利用对话上下文、用户画像和 Router 结果
 * 生成个性化的智能建议，替代原有的规则引擎建议生成。
 * <p>
 * ⭐ 使用中文分词器增强意图识别能力
 * <p>
 * 职责：
 * 1. 接收对话上下文（当前问题、历史对话、用户画像）
 * 2. 调用 LLM 生成语义相关的后续建议
 * 3. 解析并返回结构化建议列表
 * 4. 提供降级方案（规则引擎兜底）
 */
@Service
public class LLMSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(LLMSuggestionService.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final SuggestionEngine fallbackSuggestionEngine;
    private final ColdStartService coldStartService;
    private final ContextualRecommendationService contextualService;
    private final SuggestionPersonalizationService personalizationService;
    private final ChineseTokenizer tokenizer;

    // 地点提取正则（复用）
    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "(广州|深圳|成都|杭州|南京|武汉|西安|长沙|青岛|厦门|三亚|" +
                    "昆明|大理|丽江|桂林|苏州|无锡|宁波|哈尔滨|沈阳|大连|郑州|济南|" +
                    "合肥|福州|南昌|贵阳|南宁|海口|拉萨|乌鲁木齐|兰州|西宁|银川|呼和浩特|" +
                    "河北|河南|山东|山西|湖南|湖北|广东|广西|江苏|浙江|安徽|福建|江西|" +
                    "四川|贵州|云南|陕西|甘肃|青海|黑龙江|吉林|辽宁|海南|台湾|内蒙古|" +
                    "宁夏|新疆|西藏|北京|上海|天津|重庆)"
    );

    public LLMSuggestionService(ChatClient.Builder chatClientBuilder,
                                SuggestionEngine fallbackSuggestionEngine,
                                ColdStartService coldStartService,
                                ContextualRecommendationService contextualService,
                                SuggestionPersonalizationService personalizationService,
                                ChineseTokenizer tokenizer) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = new ObjectMapper();
        this.fallbackSuggestionEngine = fallbackSuggestionEngine;
        this.coldStartService = coldStartService;
        this.contextualService = contextualService;
        this.personalizationService = personalizationService;
        this.tokenizer = tokenizer;
        log.info("[LLMSuggestionService] 初始化完成");
    }

    /**
     * 生成智能建议（主入口）
     * <p>
     * 优先使用 LLM 生成语义化建议，失败时降级到规则引擎。
     *
     * @param userId         用户 ID
     * @param currentQuestion 当前问题
     * @param conversationHistory 对话历史（可选）
     * @param userProfile    用户画像文本（可选）
     * @param routerResult   Router 返回的结果摘要（可选）
     * @return 建议列表
     */
    public List<String> generateSuggestions(String userId,
                                             String currentQuestion,
                                             List<Map<String, String>> conversationHistory,
                                             String userProfile,
                                             String routerResult) {
        long startTime = System.currentTimeMillis();

        try {
            // 1. 构建 LLM Prompt
            String prompt = buildSuggestionPrompt(currentQuestion, conversationHistory, userProfile, routerResult);

            // 2. 调用 LLM
            log.debug("[LLMSuggestion] 调用 LLM 生成建议...");
            String llmResponse = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            // 3. 解析响应
            List<String> suggestions = parseSuggestions(llmResponse);

            long duration = System.currentTimeMillis() - startTime;
            log.info("[LLMSuggestion] LLM 生成 {} 条建议，耗时 {} ms", suggestions.size(), duration);

            // 4. 如果 LLM 返回空，降级到规则引擎
            if (suggestions.isEmpty()) {
                log.warn("[LLMSuggestion] LLM 返回空建议，降级到规则引擎");
                return fallbackGenerate(userId, currentQuestion, conversationHistory);
            }

            // 5. 个性化排序（如果有用户行为数据）
            if (!"anonymous".equals(userId)) {
                Map<String, String> intentMap = buildSuggestionIntentMap(suggestions);
                suggestions = personalizationService.personalizeSuggestions(userId, suggestions, intentMap);
            }

            // 6. 限制最多返回 5 条
            if (suggestions.size() > 5) {
                suggestions = suggestions.subList(0, 5);
            }

            return suggestions;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[LLMSuggestion] LLM 建议生成失败 ({} ms): {}", duration, e.getMessage());
            return fallbackGenerate(userId, currentQuestion, conversationHistory);
        }
    }

    /**
     * 构建建议生成 Prompt
     */
    private String buildSuggestionPrompt(String currentQuestion,
                                         List<Map<String, String>> conversationHistory,
                                         String userProfile,
                                         String routerResult) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("你是一个智能对话助手，擅长根据用户的当前问题和对话上下文，生成接下来用户可能想询问的后续问题建议。\n\n");

        // 用户画像
        if (userProfile != null && !userProfile.isBlank()) {
            prompt.append("【用户画像】\n").append(userProfile).append("\n\n");
        }

        // 对话历史
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            prompt.append("【最近对话历史】\n");
            int count = 0;
            for (int i = conversationHistory.size() - 1; i >= 0 && count < 5; i--, count++) {
                Map<String, String> msg = conversationHistory.get(i);
                String role = msg.getOrDefault("role", msg.getOrDefault("sender", "user"));
                String content = msg.getOrDefault("content", msg.getOrDefault("message", ""));
                prompt.append(role).append(": ").append(content).append("\n");
            }
            prompt.append("\n");
        }

        // 当前问题
        prompt.append("【当前问题】\n").append(currentQuestion).append("\n\n");

        // Router 结果摘要
        if (routerResult != null && !routerResult.isBlank()) {
            String summary = routerResult.length() > 300 ? routerResult.substring(0, 300) + "..." : routerResult;
            prompt.append("【当前回答摘要】\n").append(summary).append("\n\n");
        }

        // 输出要求
        prompt.append("【任务】\n");
        prompt.append("请生成 3-5 个用户接下来可能想询问的后续问题建议。要求：\n");
        prompt.append("1. 建议必须与当前话题相关，保持上下文连贯\n");
        prompt.append("2. 建议应该具体、可操作，避免泛泛而谈\n");
        prompt.append("3. 如果用户画像中有偏好信息，建议应体现个性化\n");
        prompt.append("4. 如果对话历史中有地点信息，建议应围绕该地点展开\n");
        prompt.append("5. 建议应覆盖不同的角度（如细节追问、相关扩展、对比选择等）\n");
        prompt.append("6. 每条建议控制在 20 字以内\n\n");

        prompt.append("【输出格式】\n");
        prompt.append("请严格按以下 JSON 格式输出，不要添加 markdown 代码块标记：\n");
        prompt.append("{\n");
        prompt.append("  \"suggestions\": [\n");
        prompt.append("    \"建议1\",\n");
        prompt.append("    \"建议2\",\n");
        prompt.append("    \"建议3\"\n");
        prompt.append("  ]\n");
        prompt.append("}\n");

        return prompt.toString();
    }

    /**
     * 解析 LLM 返回的建议 JSON
     */
    private List<String> parseSuggestions(String llmResponse) {
        List<String> suggestions = new ArrayList<>();
        if (llmResponse == null || llmResponse.isBlank()) {
            return suggestions;
        }

        try {
            // 清理 markdown 标记
            String cleaned = llmResponse.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*$", "")
                    .trim();

            JsonNode json = objectMapper.readTree(cleaned);

            if (json.has("suggestions") && json.get("suggestions").isArray()) {
                List<String> finalSuggestions = suggestions;
                json.get("suggestions").forEach(node -> {
                    String suggestion = node.asText().trim();
                    if (!suggestion.isEmpty()) {
                        finalSuggestions.add(suggestion);
                    }
                });
            }

        } catch (Exception e) {
            log.warn("[LLMSuggestion] JSON 解析失败，尝试正则提取: {}", e.getMessage());
            // 降级：尝试从文本中提取引号内容
            suggestions = extractSuggestionsByRegex(llmResponse);
        }

        return suggestions;
    }

    /**
     * 正则提取建议（降级方案）
     */
    private List<String> extractSuggestionsByRegex(String text) {
        List<String> suggestions = new ArrayList<>();
        Pattern pattern = Pattern.compile("\"([^\"]{5,30})\"");
        var matcher = pattern.matcher(text);
        while (matcher.find()) {
            String suggestion = matcher.group(1).trim();
            if (!suggestion.isEmpty() && !suggestion.contains("建议")) {
                suggestions.add(suggestion);
            }
        }
        return suggestions;
    }

    /**
     * 降级方案：使用原有规则引擎生成建议
     */
    private List<String> fallbackGenerate(String userId, String currentQuestion,
                                           List<Map<String, String>> conversationHistory) {
        log.info("[LLMSuggestion] 使用规则引擎降级生成建议");

        List<String> suggestions;

        // 冷启动判断
        int historySize = conversationHistory != null ? conversationHistory.size() : 0;
        if (coldStartService.isColdStart(userId, historySize)) {
            String location = extractLocation(currentQuestion);
            suggestions = coldStartService.generateColdStartSuggestions(location);
        }
        // 上下文推荐
        else if (historySize > 0) {
            suggestions = contextualService.generateContextualSuggestions(currentQuestion, conversationHistory);
        }
        // 策略模式
        else {
            suggestions = fallbackSuggestionEngine.generateSuggestions(currentQuestion, "auto");
        }

        // 个性化排序
        if (!suggestions.isEmpty() && !"anonymous".equals(userId)) {
            Map<String, String> intentMap = buildSuggestionIntentMap(suggestions);
            suggestions = personalizationService.personalizeSuggestions(userId, suggestions, intentMap);
        }

        if (suggestions.size() > 5) {
            suggestions = suggestions.subList(0, 5);
        }

        return suggestions;
    }

    /**
     * 从问题中提取地点
     */
    private String extractLocation(String question) {
        if (question == null) return null;
        var matcher = LOCATION_PATTERN.matcher(question);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 构建建议与意图的映射
     * <p>
     * ⭐ 使用中文分词器增强意图识别能力
     */
    private Map<String, String> buildSuggestionIntentMap(List<String> suggestions) {
        Map<String, String> intentMap = new HashMap<>();
        for (String suggestion : suggestions) {
            // ⭐ 使用分词器进行意图识别
            if (tokenizer.containsAnyKeyword(suggestion, Set.of("天气", "气温", "温度", "下雨"))) {
                intentMap.put(suggestion, "WEATHER");
            } else if (tokenizer.containsAnyKeyword(suggestion, Set.of("美食", "餐厅", "吃", "觅食", "馆子"))) {
                intentMap.put(suggestion, "FOOD");
            } else if (tokenizer.containsAnyKeyword(suggestion, Set.of("旅游", "景点", "行程", "打卡", "游玩"))) {
                intentMap.put(suggestion, "TRAVEL");
            } else if (tokenizer.containsAnyKeyword(suggestion, Set.of("交通", "地铁", "怎么去", "咋走"))) {
                intentMap.put(suggestion, "TRANSPORT");
            } else {
                intentMap.put(suggestion, "GENERAL");
            }
        }
        return intentMap;
    }
}
