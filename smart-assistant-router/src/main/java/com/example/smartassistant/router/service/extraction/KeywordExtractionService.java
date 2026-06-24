/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.router.service.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键词提取服务
 * 从用户问题中提取关键信息，用于构建精简的 instruction，减少 token 消耗
 */
@Service
public class KeywordExtractionService {
    
    private static final Logger log = LoggerFactory.getLogger(KeywordExtractionService.class);
    
    // 轻量模型用于 LLM 辅助提取
    private final Optional<ChatClient> chatClient;
    
    // 缓存：问题 -> 精简指令（使用 ConcurrentHashMap 保证线程安全）
    private final Map<String, String> extractionCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 1000; // 最大缓存条目数
    
    // 自适应学习：关键词 -> 权重（用于意图识别优先级调整）
    private final Map<String, Double> keywordWeights = new ConcurrentHashMap<>();
    private static final double INITIAL_WEIGHT = 1.0;
    private static final double WEIGHT_INCREMENT = 0.1; // 每次成功增加

    // ⭐ 缓存命中率统计
    private final AtomicLong totalRequests = new AtomicLong(0);      // 总请求数
    private final AtomicLong cacheHits = new AtomicLong(0);           // 缓存命中数

    public KeywordExtractionService(@Qualifier("lightChatModel") ChatModel lightModel) {
        this.chatClient = Optional.of(ChatClient.create(lightModel));
    }
    
    // 地点提取正则（匹配常见城市和省份）
    private static final Pattern LOCATION_PATTERN = Pattern.compile(
        "(北京|上海|广州|深圳|杭州|南京|成都|重庆|西安|武汉|天津|苏州|青岛|大连|" +
        "厦门|三亚|昆明|大理|丽江|桂林|哈尔滨|沈阳|长春|郑州|济南|合肥|福州|" +
        "南昌|贵阳|南宁|海口|拉萨|乌鲁木齐|兰州|西宁|银川|呼和浩特|" +
        "河北|河南|山东|山西|湖南|湖北|广东|广西|江苏|浙江|安徽|福建|江西|" +
        "四川|贵州|云南|陕西|甘肃|青海|黑龙江|吉林|辽宁|海南|台湾|内蒙古|" +
        "宁夏|新疆|西藏)"
    );
    
    // 时间提取正则
    private static final Pattern TIME_PATTERN = Pattern.compile(
        "(今天|明天|后天|大后天|本周|下周|本月|下月|" +
        "周一|周二|周三|周四|周五|周六|周日|" +
        "早上|上午|中午|下午|晚上|凌晨|" +
        "\\d{1,2}月\\d{1,2}日|\\d{1,2}号)"
    );
    
    // 美食相关关键词
    private static final Set<String> FOOD_KEYWORDS = Set.of(
        "美食", "餐厅", "吃什么", "特色菜", "小吃", "火锅", "烧烤",
        "推荐餐厅", "附近美食", "菜系", "好吃", "饭店", "馆子"
    );
    
    // 天气相关关键词
    private static final Set<String> WEATHER_KEYWORDS = Set.of(
        "天气", "气温", "下雨", "晴天", "阴天", "温度", "气候",
        "适合出门", "穿衣", "预报", "雾霾", "风力"
    );
    
    // 旅游相关关键词
    private static final Set<String> TRAVEL_KEYWORDS = Set.of(
        "旅游", "旅行", "出去玩", "度假", "景点", "景区", "游玩",
        "周末", "假期", "出行", "自驾游", "跟团", "攻略", "行程"
    );
    
    /**
     * 从完整的 Prompt 中提取关键词并构建精简 instruction
     * 支持两种格式：
     * 1. JSON 格式（新）- StructuredPrompt
     * 2. 文本标记格式（旧）- 【用户画像】【历史对话】【当前问题】
     * 
     * @param fullPrompt 完整的 Prompt（JSON 或文本格式）
     * @return 优化后的 instruction
     */
    public String extractKeywordsFromFullPrompt(String fullPrompt) {
        if (fullPrompt == null || fullPrompt.isEmpty()) {
            return "";
        }
        
        // 检测是否为 JSON 格式
        if (fullPrompt.trim().startsWith("{")) {
            log.debug("[KeywordExtraction] 检测到 JSON 格式 Prompt");
            return extractKeywordsFromJsonPrompt(fullPrompt);
        }
        
        // 降级：使用文本标记格式解析
        log.debug("[KeywordExtraction] 使用文本标记格式解析");
        return extractKeywordsFromTextPrompt(fullPrompt);
    }
    
    /**
     * 从 JSON 格式的 Prompt 中提取关键词
     */
    private String extractKeywordsFromJsonPrompt(String jsonPrompt) {
        long parseStart = System.currentTimeMillis();
        
        try {
            // 反序列化 JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonPromptData promptData = mapper.readValue(jsonPrompt, JsonPromptData.class);
            
            long parseEnd = System.currentTimeMillis();
            log.debug("[KeywordExtraction] JSON 解析成功: version={}, requestId={}, parseTime={}ms",
                promptData.version, 
                promptData.metadata != null ? promptData.metadata.requestId : "N/A",
                parseEnd - parseStart);
            
            StringBuilder optimizedInstruction = new StringBuilder();
            
            // Step 1: 完整保留用户画像
            if (promptData.userProfile != null) {
                optimizedInstruction.append("【用户画像】\n");
                if (promptData.userProfile.preferences != null && !promptData.userProfile.preferences.isEmpty()) {
                    optimizedInstruction.append("偏好: ").append(promptData.userProfile.preferences).append("\n");
                }
                if (promptData.userProfile.historyBehavior != null && !promptData.userProfile.historyBehavior.isEmpty()) {
                    optimizedInstruction.append("历史: ").append(promptData.userProfile.historyBehavior).append("\n");
                }
                optimizedInstruction.append("\n");
                log.debug("[KeywordExtraction] 保留用户画像");
            }
            
            // Step 2: 对历史对话进行关键词提取
            if (promptData.conversationHistory != null && !promptData.conversationHistory.isEmpty()) {
                String optimizedHistory = extractKeywordsFromJsonHistory(promptData.conversationHistory);
                optimizedInstruction.append("【历史对话】\n");
                optimizedInstruction.append(optimizedHistory);
                optimizedInstruction.append("\n\n");
                log.debug("[KeywordExtraction] 历史对话优化完成");
            }
            
            // Step 3: 对当前问题进行关键词提取
            if (promptData.currentQuestion != null && !promptData.currentQuestion.isEmpty()) {
                String keywordInstruction = extractKeywordsAsInstruction(promptData.currentQuestion);
                
                if (keywordInstruction != null && !keywordInstruction.isEmpty()) {
                    optimizedInstruction.append("【当前问题】\n").append(keywordInstruction);
                    log.debug("[KeywordExtraction] 问题精简完成");
                } else {
                    optimizedInstruction.append("【当前问题】\n").append(promptData.currentQuestion);
                }
            }
            
            String result = optimizedInstruction.toString().trim();
            
            // 计算节省效果
            int originalLength = jsonPrompt.length();
            int optimizedLength = result.length();
            double reduction = originalLength > 0 ? 
                (1.0 - (double) optimizedLength / originalLength) * 100 : 0;
            
            log.info("[KeywordExtraction] JSON Prompt 优化: {} 字符 → {} 字符 (节省 {}%)",
                originalLength, optimizedLength, reduction);
            
            return result;
            
        } catch (Exception e) {
            long parseEnd = System.currentTimeMillis();
            log.error("[KeywordExtraction] JSON 解析失败，耗时={}ms, 错误: {}", 
                parseEnd - parseStart, e.getMessage());
            // 降级处理：当作普通文本
            return extractKeywordsFromTextPrompt(jsonPrompt);
        }
    }
    
    /**
     * 从 JSON 历史对话中提取关键词
     */
    private String extractKeywordsFromJsonHistory(List<JsonMessage> messages) {
        StringBuilder optimizedHistory = new StringBuilder();
        int roundCount = 0;
        
        for (JsonMessage msg : messages) {
            if ("user".equals(msg.role)) {
                // 对用户输入进行关键词提取
                String keywordContent = extractKeywordsFromUserMessage(msg.content);
                if (!keywordContent.isEmpty()) {
                    if (roundCount > 0) optimizedHistory.append("\n");
                    optimizedHistory.append("用户: ").append(keywordContent);
                    roundCount++;
                }
            } else if ("agent".equals(msg.role)) {
                // Agent 回复只保留核心信息（前 50 字符）
                String summary = truncate(msg.content, 50);
                if (!summary.isEmpty()) {
                    if (roundCount > 0) optimizedHistory.append("\n");
                    optimizedHistory.append("Agent: ").append(summary);
                    roundCount++;
                }
            }
        }
        
        return optimizedHistory.toString();
    }
    
    /**
     * 从文本标记格式的 Prompt 中提取关键词（原有逻辑）
     */
    private String extractKeywordsFromTextPrompt(String fullPrompt) {
        if (fullPrompt == null || fullPrompt.isEmpty()) {
            return "";
        }
        
        // Step 1: 解析 Prompt 结构
        PromptParts parts = parsePromptStructure(fullPrompt);
        
        StringBuilder optimizedInstruction = new StringBuilder();
        
        // Step 2: 完整保留用户画像
        if (parts.userProfile != null && !parts.userProfile.isEmpty()) {
            optimizedInstruction.append("【用户画像】\n");
            optimizedInstruction.append(parts.userProfile);
            optimizedInstruction.append("\n\n");
            log.debug("[KeywordExtraction] 保留用户画像: {} 字符", parts.userProfile.length());
        }
        
        // Step 3: 对历史对话进行关键词提取（精简但保留关键信息）
        if (parts.history != null && !parts.history.isEmpty()) {
            String optimizedHistory = extractKeywordsFromHistory(parts.history);
            optimizedInstruction.append("【历史对话】\n");
            optimizedInstruction.append(optimizedHistory);
            optimizedInstruction.append("\n\n");
            log.debug("[KeywordExtraction] 历史对话优化: {} 字符 → {} 字符", 
                parts.history.length(), optimizedHistory.length());
        }
        
        // Step 4: 对当前问题进行关键词提取
        if (parts.currentQuestion != null && !parts.currentQuestion.isEmpty()) {
            String keywordInstruction = extractKeywordsAsInstruction(parts.currentQuestion);
            
            if (keywordInstruction != null && !keywordInstruction.isEmpty()) {
                optimizedInstruction.append("【当前问题】\n").append(keywordInstruction);
                log.debug("[KeywordExtraction] 问题精简: '{}' → '{}'", 
                    truncate(parts.currentQuestion, 50), keywordInstruction);
            } else {
                // 如果无法提取关键词，保留原问题
                optimizedInstruction.append("【当前问题】\n").append(parts.currentQuestion);
                log.debug("[KeywordExtraction] 无法提取关键词，保留原问题");
            }
        }
        
        String result = optimizedInstruction.toString().trim();
        
        // 计算节省效果
        int originalLength = fullPrompt.length();
        int optimizedLength = result.length();
        double reduction = (1.0 - (double) optimizedLength / originalLength) * 100;
        
        log.info("[KeywordExtraction] Prompt 优化: {} 字符 → {} 字符 (节省 {}%)",
            originalLength, optimizedLength, reduction);
        
        return result;
    }
    
    /**
     * 解析 Prompt 结构
     */
    private PromptParts parsePromptStructure(String fullPrompt) {
        PromptParts parts = new PromptParts();
        
        // 查找【当前问题】标记
        int questionStart = fullPrompt.indexOf("【当前问题】");
        
        if (questionStart != -1) {
            // 提取当前问题（标记之后的所有内容）
            parts.currentQuestion = fullPrompt.substring(questionStart + "【当前问题】".length()).trim();
            
            // 提取用户画像和历史对话（标记之前的所有内容）
            String beforeQuestion = fullPrompt.substring(0, questionStart).trim();
            
            // 查找【历史对话】标记
            int historyStart = beforeQuestion.indexOf("【历史对话】");
            
            if (historyStart != -1) {
                // 有历史对话部分
                parts.userProfile = beforeQuestion.substring(0, historyStart)
                        .replace("【用户画像】", "")
                        .trim();
                parts.history = beforeQuestion.substring(historyStart + "【历史对话】".length())
                        .trim();
            } else {
                // 没有历史对话，只有用户画像
                parts.userProfile = beforeQuestion.replace("【用户画像】", "").trim();
                parts.history = "";
            }
        } else {
            // 没有标记，整个作为当前问题
            parts.currentQuestion = fullPrompt;
            parts.userProfile = "";
            parts.history = "";
        }
        
        return parts;
    }
    
    /**
     * 从历史对话中提取关键词（精简但保留关键信息）
     * 策略：
     * 1. 保留每轮对话的核心内容（地点、时间、意图）
     * 2. 去除冗余的寒暄和修饰词
     * 3. 压缩格式，减少 token
     */
    private String extractKeywordsFromHistory(String history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        
        StringBuilder optimizedHistory = new StringBuilder();
        
        // 按行分割对话
        String[] lines = history.split("\n");
        int roundCount = 0;
        
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) continue;
            
            // 检测是否为对话行（以“用户:”或“Agent:”开头）
            if (trimmedLine.startsWith("用户:") || trimmedLine.startsWith("Agent:")) {
                String speaker = trimmedLine.startsWith("用户:") ? "用户" : "Agent";
                String content = trimmedLine.substring(trimmedLine.indexOf(":") + 1).trim();
                
                if (speaker.equals("用户")) {
                    // 对用户输入进行关键词提取
                    String keywordContent = extractKeywordsFromUserMessage(content);
                    if (!keywordContent.isEmpty()) {
                        if (roundCount > 0) optimizedHistory.append("\n");
                        optimizedHistory.append("用户: ").append(keywordContent);
                        roundCount++;
                    }
                } else {
                    // Agent 回复只保留核心信息（前 50 字符）
                    String summary = truncate(content, 50);
                    if (!summary.isEmpty()) {
                        if (roundCount > 0) optimizedHistory.append("\n");
                        optimizedHistory.append("Agent: ").append(summary);
                        roundCount++;
                    }
                }
            }
        }
        
        return optimizedHistory.toString();
    }
    
    /**
     * 从用户消息中提取关键词
     */
    private String extractKeywordsFromUserMessage(String message) {
        // 尝试提取地点
        String location = extractLocation(message);
        // 尝试提取时间
        String time = extractTime(message);
        // 尝试提取意图
        String intent = detectIntent(message);
        
        StringBuilder keywords = new StringBuilder();
        boolean hasContent = false;
        
        if (location != null) {
            keywords.append("地点:").append(location);
            hasContent = true;
        }
        
        if (time != null) {
            if (hasContent) keywords.append(";");
            keywords.append("时间:").append(time);
            hasContent = true;
        }
        
        if (intent != null) {
            if (hasContent) keywords.append(";");
            keywords.append("意图:").append(intent);
            hasContent = true;
        }
        
        // 如果没有提取到关键词，保留原始消息的前 30 字符
        if (!hasContent) {
            return truncate(message, 30);
        }
        
        return keywords.toString();
    }
    
    /**
     * Prompt 各部分
     */
    private static class PromptParts {
        String userProfile = "";    // 用户画像
        String history = "";        // 历史对话
        String currentQuestion = ""; // 当前问题
    }
    
    /**
     * JSON Prompt 数据结构（用于反序列化）
     */
    private static class JsonPromptData {
        public String version;
        public JsonMetadata metadata;
        public JsonUserProfile userProfile;
        public List<JsonMessage> conversationHistory;
        public String currentQuestion;
        public Boolean compressed;
    }
    
    /**
     * JSON 元数据
     */
    private static class JsonMetadata {
        public String requestId;
        public Long timestamp;
        public String clientId;
        public Long userId;
        public String sessionId;
        public String extra;
    }
    
    /**
     * JSON 用户画像
     */
    private static class JsonUserProfile {
        public String preferences;
        public String historyBehavior;
        public String additionalInfo;
    }
    
    /**
     * JSON 对话消息
     */
    private static class JsonMessage {
        public String role;      // "user" or "agent"
        public String content;
        public Long timestamp;
    }
    
    /**
     * 截断字符串用于日志显示
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
    
    /**
     * 从用户问题中提取关键词并构建精简指令
     * （原有方法，用于单独的问题处理）
     * 增强版：加入缓存机制
     * 
     * @param question 原始问题
     * @return 精简后的 instruction
     */
    public String extractKeywordsAsInstruction(String question) {
        if (question == null || question.isEmpty()) {
            return "";
        }
        
        // Step 1: 检查缓存
        totalRequests.incrementAndGet(); // ⭐ 记录总请求
        String cached = extractionCache.get(question);
        if (cached != null) {
            cacheHits.incrementAndGet(); // ⭐ 记录缓存命中
            log.debug("[KeywordExtraction] 缓存命中: '{}'", truncate(question, 30));
            return cached;
        }
        
        // Step 2: 执行提取逻辑
        Map<String, String> keywords = new LinkedHashMap<>();
        
        // 1. 提取地点
        String location = extractLocation(question);
        if (location != null) {
            keywords.put("地点", location);
        }
        
        // 2. 提取时间
        String time = extractTime(question);
        if (time != null) {
            keywords.put("时间", time);
        }
        
        // 3. 识别意图类型
        String intent = detectIntent(question);
        if (intent != null) {
            keywords.put("意图", intent);
        }
        
        // 4. 提取核心动作/需求
        String action = extractAction(question);
        if (action != null) {
            keywords.put("需求", action);
        }
        
        // 5. 构建精简指令
        String instruction = buildCompactInstruction(keywords);
        
        // Step 3: 存入缓存（如果缓存已满，清除最旧的 10%）
        if (extractionCache.size() >= MAX_CACHE_SIZE) {
            clearOldestCacheEntries(); // 清除 100 条
        }
        extractionCache.put(question, instruction);
        
        log.debug("[KeywordExtraction] 原始问题: '{}', 精简指令: '{}'", 
            truncate(question, 50), instruction);
        
        return instruction;
    }
    
    /**
     * 清除最旧的缓存条目
     */
    private void clearOldestCacheEntries() {
        // 简单策略：随机清除（实际生产环境可使用 LRU 算法）
        List<String> keys = new ArrayList<>(extractionCache.keySet());
        Collections.shuffle(keys);
        keys.subList(0, Math.min(100, keys.size())).forEach(extractionCache::remove);
        log.debug("[KeywordExtraction] 清理缓存: 移除 {} 条旧记录", 100);
    }
    
    /**
     * 获取缓存统计信息
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cacheSize", extractionCache.size());
        stats.put("maxCacheSize", MAX_CACHE_SIZE);
        stats.put("hitRate", calculateHitRate());
        return stats;
    }
    
    /**
     * 计算缓存命中率
     * ⭐ 基于真实的 totalRequests 和 cacheHits 统计
     */
    private double calculateHitRate() {
        long total = totalRequests.get();
        long hits = cacheHits.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) hits / total;
    }
    
    /**
     * 清空缓存
     */
    public void clearCache() {
        extractionCache.clear();
        // ⭐ 重置统计（可选：保留统计继续累计）
        log.info("[KeywordExtraction] 缓存已清空，当前统计: {}", getCacheStats());
    }
    
    /**
     * 获取关键词权重（用于监控）
     */
    public Map<String, Double> getKeywordWeights() {
        return new HashMap<>(keywordWeights);
    }

    /**
     * 提取地点信息
     */
    private String extractLocation(String text) {
        Matcher matcher = LOCATION_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
    
    /**
     * 提取时间信息
     */
    private String extractTime(String text) {
        Matcher matcher = TIME_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
    
    /**
     * 识别意图类型
     * 增强版：对于复杂问题，使用 LLM 辅助判断 + 自适应权重
     */
    private String detectIntent(String question) {
        String lowerQuestion = question.toLowerCase();
        
        // Step 1: 基于关键词和权重判断
        Map<String, Double> intentScores = calculateIntentScores(lowerQuestion);
        
        if (!intentScores.isEmpty()) {
            // 选择得分最高的意图
            Optional<Map.Entry<String, Double>> bestIntent = intentScores.entrySet().stream()
                    .max(Map.Entry.comparingByValue());
            
            if (bestIntent.isPresent() && bestIntent.get().getValue() > 0.5) {
                log.debug("[KeywordExtraction] 基于权重识别意图: {} (score={})", 
                    bestIntent.get().getKey(), bestIntent.get().getValue());
                return bestIntent.get().getKey();
            }
        }
        
        // Step 2: 如果无法判断且问题较复杂，使用 LLM 辅助
        if (isComplexQuestion(question) && chatClient.isPresent()) {
            String llmIntent = detectIntentWithLLM(question);
            if (llmIntent != null) {
                // 记录 LLM 的识别结果，用于后续权重调整
                learnFromLLMResult(lowerQuestion, llmIntent);
                return llmIntent;
            }
        }
        
        return null;
    }
    
    /**
     * 计算各意图的得分（基于关键词权重）
     */
    private Map<String, Double> calculateIntentScores(String lowerQuestion) {
        Map<String, Double> scores = new HashMap<>();

        // 美食意图
        double foodScore = FOOD_KEYWORDS.stream()
                .filter(lowerQuestion::contains)
                .mapToDouble(kw -> keywordWeights.getOrDefault(kw, INITIAL_WEIGHT))
                .sum();
        if (foodScore > 0) scores.put("美食推荐", foodScore);

        // 天气意图
        double weatherScore = WEATHER_KEYWORDS.stream()
                .filter(lowerQuestion::contains)
                .mapToDouble(kw -> keywordWeights.getOrDefault(kw, INITIAL_WEIGHT))
                .sum();
        if (weatherScore > 0) scores.put("天气查询", weatherScore);

        // 旅游意图
        double travelScore = TRAVEL_KEYWORDS.stream()
                .filter(lowerQuestion::contains)
                .mapToDouble(kw -> keywordWeights.getOrDefault(kw, INITIAL_WEIGHT))
                .sum();
        if (travelScore > 0) scores.put("旅游规划", travelScore);

        // 兜底：根据关键词直接匹配（无权重时也能识别）
        if (scores.isEmpty()) {
            if (FOOD_KEYWORDS.stream().anyMatch(lowerQuestion::contains)) {
                scores.put("美食推荐", INITIAL_WEIGHT);
            } else if (WEATHER_KEYWORDS.stream().anyMatch(lowerQuestion::contains)) {
                scores.put("天气查询", INITIAL_WEIGHT);
            } else if (TRAVEL_KEYWORDS.stream().anyMatch(lowerQuestion::contains)) {
                scores.put("旅游规划", INITIAL_WEIGHT);
            }
        }

        return scores;
    }
    
    /**
     * 从 LLM 结果中学习，调整关键词权重
     */
    private void learnFromLLMResult(String lowerQuestion, String llmIntent) {
        // 根据 LLM 识别的意图，提升相关关键词的权重
        Set<String> relevantKeywords = new HashSet<>();
        
        switch (llmIntent) {
            case "美食推荐":
                relevantKeywords.addAll(FOOD_KEYWORDS);
                break;
            case "天气查询":
                relevantKeywords.addAll(WEATHER_KEYWORDS);
                break;
            case "旅游规划":
                relevantKeywords.addAll(TRAVEL_KEYWORDS);
                break;
        }
        
        // 提升出现在问题中的关键词权重
        for (String keyword : relevantKeywords) {
            if (lowerQuestion.contains(keyword)) {
                double currentWeight = keywordWeights.getOrDefault(keyword, INITIAL_WEIGHT);
                keywordWeights.put(keyword, Math.min(currentWeight + WEIGHT_INCREMENT, 3.0));
                log.debug("[KeywordExtraction] 提升关键词权重: '{}' = {}",
                    keyword, keywordWeights.get(keyword));
            }
        }
    }
    
    /**
     * 判断是否为复杂问题
     */
    private boolean isComplexQuestion(String question) {
        // 条件1: 长度超过 30 字符
        // 条件2: 包含多个问号或感叹号
        // 条件3: 包含多个动词或意图词
        int questionMarks = (int) question.chars().filter(c -> c == '?' || c == '？').count();
        int exclamationMarks = (int) question.chars().filter(c -> c == '!').count();
        
        return question.length() > 30 || 
               (questionMarks + exclamationMarks) > 1 ||
               countIntentKeywords(question) > 1;
    }
    
    /**
     * 统计意图关键词数量
     */
    private int countIntentKeywords(String question) {
        String lowerQuestion = question.toLowerCase();
        int count = 0;
        
        if (FOOD_KEYWORDS.stream().anyMatch(lowerQuestion::contains)) count++;
        if (WEATHER_KEYWORDS.stream().anyMatch(lowerQuestion::contains)) count++;
        if (TRAVEL_KEYWORDS.stream().anyMatch(lowerQuestion::contains)) count++;
        
        return count;
    }
    
    /**
     * 使用 LLM 辅助识别意图
     */
    private String detectIntentWithLLM(String question) {
        try {
            log.debug("[KeywordExtraction] 使用 LLM 辅助识别复杂问题意图");
            
            String prompt = String.format("""
                请分析以下用户问题的意图类型，从以下选项中选择最匹配的一个：
                - 美食推荐：涉及餐厅、菜品、小吃、饮食等
                - 天气查询：涉及气温、下雨、晴天、气候等
                - 旅游规划：涉及景点、行程、旅行、度假等
                - 其他：不属于以上三类
                
                用户问题：%s
                
                只输出意图类型名称（如：美食推荐），不要输出其他内容。
                """, question);
            
            String result = chatClient.get()
                    .prompt(prompt)
                    .call()
                    .content();
            
            if (result != null && !result.isEmpty()) {
                String intent = result.trim();
                log.debug("[KeywordExtraction] LLM 识别结果: {}", intent);
                
                // 验证返回结果是否有效
                if (intent.contains("美食") || intent.contains("food")) {
                    return "美食推荐";
                } else if (intent.contains("天气") || intent.contains("weather")) {
                    return "天气查询";
                } else if (intent.contains("旅游") || intent.contains("travel")) {
                    return "旅游规划";
                }
            }
            
        } catch (Exception e) {
            log.warn("[KeywordExtraction] LLM 意图识别失败，降级为关键词匹配: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * 提取核心动作/需求
     */
    private String extractAction(String question) {
        String lowerQuestion = question.toLowerCase();
        
        // 常见动作词
        if (lowerQuestion.contains("推荐") || lowerQuestion.contains("介绍")) {
            return "推荐";
        }
        if (lowerQuestion.contains("查询") || lowerQuestion.contains("查看")) {
            return "查询";
        }
        if (lowerQuestion.contains("规划") || lowerQuestion.contains("制定")) {
            return "规划";
        }
        if (lowerQuestion.contains("怎么去") || lowerQuestion.contains("如何到达")) {
            return "路线查询";
        }
        
        return null;
    }
    
    /**
     * 构建精简指令
     * 格式：地点: XX; 时间: XX; 意图: XX; 需求: XX
     */
    private String buildCompactInstruction(Map<String, String> keywords) {
        if (keywords.isEmpty()) {
            return "";
        }
        
        StringBuilder instruction = new StringBuilder();
        boolean first = true;
        
        for (Map.Entry<String, String> entry : keywords.entrySet()) {
            if (!first) {
                instruction.append("; ");
            }
            instruction.append(entry.getKey()).append(": ").append(entry.getValue());
            first = false;
        }
        
        return instruction.toString();
    }
    
    /**
     * 批量提取多个问题的关键词
     * 
     * @param questions 问题列表
     * @return 每个问题对应的精简指令
     */
    public List<String> batchExtractKeywords(List<String> questions) {
        return questions.stream()
                .map(this::extractKeywordsAsInstruction)
                .toList();
    }
}
