package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.consumer.entity.UserProfile;
import com.example.smartassistant.consumer.mapper.UserProfileMapper;
import com.example.smartassistant.consumer.service.UserPreferenceVectorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 用户画像服务
 * 负责提取、更新和查询用户偏好信息
 */
@Service
public class UserProfileService {
    
    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    // ⭐ Phase 3 权重配置
    private static final int WEIGHT_INCREMENT = 1;           // 每次增加权重
    private static final int WEIGHT_MAX = 100;              // 权重上限
    private static final int TOP_PREFERENCES_LIMIT = 5;     // Top-N 偏好

    // 权重级别阈值
    private static final int WEIGHT_LEVEL_HIGH = 8;       // 高权重
    private static final int WEIGHT_LEVEL_MEDIUM = 4;       // 中权重

    private final UserProfileMapper userProfileMapper;
    private final LLMPreferenceExtractor llmExtractor;
    private final UserPreferenceVectorService vectorService;

    // 偏好关键词模式
    private static final Map<String, Pattern> PREFERENCE_PATTERNS = new HashMap<>();
    
    static {
        // 美食偏好
        PREFERENCE_PATTERNS.put("food_spicy", Pattern.compile("(辣|麻辣|香辣|酸辣|微辣)"));
        PREFERENCE_PATTERNS.put("food_light", Pattern.compile("(清淡|清蒸|少油|少盐)"));
        PREFERENCE_PATTERNS.put("food_sweet", Pattern.compile("(甜|甜品|糖水)"));
        
        // 旅行偏好
        PREFERENCE_PATTERNS.put("travel_nature", Pattern.compile("(自然|山水|风景|户外|徒步)"));
        PREFERENCE_PATTERNS.put("travel_culture", Pattern.compile("(文化|历史|古迹|博物馆)"));
        PREFERENCE_PATTERNS.put("travel_relax", Pattern.compile("(休闲|度假|放松|温泉)"));
        
        // 预算关键词
        PREFERENCE_PATTERNS.put("budget_low", Pattern.compile("(便宜|经济|实惠|性价比)"));
        PREFERENCE_PATTERNS.put("budget_high", Pattern.compile("(高端|豪华|精品|五星)"));
        
        // 饮食限制
        PREFERENCE_PATTERNS.put("diet_vegetarian", Pattern.compile("(素食|素菜|不吃肉)"));
        PREFERENCE_PATTERNS.put("diet_halal", Pattern.compile("(清真|回族)"));
    }
    
    public UserProfileService(UserProfileMapper userProfileMapper,
                              LLMPreferenceExtractor llmExtractor,
                              UserPreferenceVectorService vectorService,
                              @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.userProfileMapper = userProfileMapper;
        this.llmExtractor = llmExtractor;
        this.vectorService = vectorService;
    }
    
    /**
     * 从问题中提取偏好信息并更新用户画像
     */
    @Transactional
    public void extractAndUpdatePreferences(Long userId, String question, String extractedLocation) {
        if (userId == null || question == null) {
            return;
        }
        
        // 获取或创建用户画像
        UserProfile profile = userProfileMapper.findByUserId(userId);
        if (profile == null) {
            profile = new UserProfile();
            profile.setUserId(userId);
            profile.setTotalQueries(0);
        }
        
        // 使用 LLM 提取偏好（Phase 2）
        LLMPreferenceExtractor.ExtractedPreferences llmPrefs = llmExtractor.extract(question);
        
        // 合并 LLM 提取的地点（如果正则未提取到）
        String location = extractedLocation;
        if ((location == null || location.isBlank()) && llmPrefs.getLocation() != null) {
            location = llmPrefs.getLocation();
        }
        
        // 提取偏好
        Set<String> foodPrefs = new HashSet<>(llmPrefs.getFoodPreferences());
        Set<String> travelPrefs = new HashSet<>(llmPrefs.getTravelPreferences());
        String budget = convertBudget(llmPrefs.getBudget());
        List<String> dietaryRestrictions = llmPrefs.getDietaryRestrictions();
        
        // 如果 LLM 未提取到，降级到正则提取
        if (foodPrefs.isEmpty()) {
            foodPrefs = extractPreferences(question, "food_");
        }
        if (travelPrefs.isEmpty()) {
            travelPrefs = extractPreferences(question, "travel_");
        }
        if (budget == null) {
            budget = extractBudget(question);
        }
        if (dietaryRestrictions.isEmpty()) {
            dietaryRestrictions = extractDietaryRestrictions(question);
        }
        
        // ⭐ 合并到现有偏好并更新权重
        Map<String, Integer> weights = profile.getPreferenceWeightsMap();
        profile.setFoodPreferencesArray(mergePreferencesWithWeight(
                profile.getFoodPreferencesArray(), foodPrefs, weights));
        profile.setTravelPreferencesArray(mergePreferencesWithWeight(
                profile.getTravelPreferencesArray(), travelPrefs, weights));
        profile.setPreferenceWeightsMap(weights);
        
        // ⭐ 更新地点频率统计
        if (location != null && !location.isBlank()) {
            addToPreferredLocations(profile, location);
            updateLocationWeight(profile, location);
        }
        
        // 更新预算（如果检测到）
        if (budget != null) {
            profile.setBudgetRange(budget);
        }
        
        // 更新饮食限制（饮食限制通常不变，只累加不降权）
        if (!dietaryRestrictions.isEmpty()) {
            for (String diet : dietaryRestrictions) {
                int newWeight = weights.getOrDefault(diet, 0) + WEIGHT_INCREMENT;
                weights.put(diet, Math.min(newWeight, WEIGHT_MAX));
            }
        }
        
        // 更新统计信息
        profile.setTotalQueries(profile.getTotalQueries() + 1);
        profile.setLastQueryAt(LocalDateTime.now());
        
        // 保存（使用 try-catch 防止损坏数据导致主流程失败）
        try {
            if (profile.getId() == null) {
                profile.setCreatedAt(LocalDateTime.now());
                profile.setUpdatedAt(LocalDateTime.now());
                userProfileMapper.insertProfile(profile);
            } else {
                profile.setUpdatedAt(LocalDateTime.now());
                userProfileMapper.updateProfile(profile);
            }
        } catch (Exception e) {
            log.warn("[UserProfile] 保存用户画像失败，不影响主流程: {}", e.getMessage());
            // 尝试重置损坏的数据
            try {
                if (profile.getId() != null) {
                    profile.setPreferredLocationsArray(new String[0]);
                    profile.setFoodPreferencesArray(new String[0]);
                    profile.setTravelPreferencesArray(new String[0]);
                    profile.setDietaryRestrictionsArray(new String[0]);
                    profile.setAdditionalPreferences("{}");
                    profile.setUpdatedAt(LocalDateTime.now());
                    userProfileMapper.updateProfile(profile);
                }
            } catch (Exception e2) {
                log.warn("[UserProfile] 重置画像也失败: {}", e2.getMessage());
            }
        }

        // ⭐ 异步同步到向量库
        if (profile.getId() != null) {
            vectorService.syncAllPreferenceVectors(
                    profile.getId(),
                    profile.getFoodPreferencesArray(),
                    profile.getTravelPreferencesArray(),
                    profile.getDietaryRestrictionsArray(),
                    profile.getBudgetRange()
            );
        }

        log.info("[UserProfile] 用户 {} 画像已更新: 总查询={}, 地点偏好={}, 美食偏好={}", 
                userId, profile.getTotalQueries(), 
                Arrays.toString(profile.getPreferredLocationsArray()),
                Arrays.toString(profile.getFoodPreferencesArray()));
    }
    
    /**
     * 构建用户画像 Prompt（用于注入到 Agent）
     * 从 user_preference_vectors 表查询用户偏好
     */
    public String buildUserProfilePrompt(Long userId) {
        if (userId == null) {
            return "";
        }
        
        // 从 user_preference_vectors 表查询用户画像
        UserProfile profile = vectorService.buildUserProfileFromVectors(userId);
        if (profile == null) {
            return "";
        }
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("【用户历史信息】\n");
        
        // 地点偏好
        String[] prefLocs = profile.getPreferredLocationsArray();
        if (prefLocs != null && prefLocs.length > 0) {
            prompt.append("- 常用地点: ").append(String.join(", ", prefLocs)).append("\n");
        }
        
        // 美食偏好（带权重）
        String[] foodPrefs = profile.getFoodPreferencesArray();
        if (foodPrefs != null && foodPrefs.length > 0) {
            Map<String, Integer> weights = profile.getPreferenceWeightsMap();
            String foodPrefsWithWeight = buildWeightedPreferenceString(foodPrefs, weights);
            prompt.append("- 美食偏好: ").append(foodPrefsWithWeight).append("\n");
        }

        // 旅行偏好（带权重）
        String[] travelPrefs = profile.getTravelPreferencesArray();
        if (travelPrefs != null && travelPrefs.length > 0) {
            Map<String, Integer> weights = profile.getPreferenceWeightsMap();
            String travelPrefsWithWeight = buildWeightedPreferenceString(travelPrefs, weights);
            prompt.append("- 旅行偏好: ").append(travelPrefsWithWeight).append("\n");
        }
        
        // 饮食限制
        String[] dietRes = profile.getDietaryRestrictionsArray();
        if (dietRes != null && dietRes.length > 0) {
            prompt.append("- 饮食限制: ").append(String.join(", ", dietRes)).append("\n");
        }
        
        // 预算范围
        if (profile.getBudgetRange() != null) {
            prompt.append("- 预算范围: ").append(profile.getBudgetRange()).append("\n");
        }
        
        // 总查询次数
        prompt.append("- 历史查询: ").append(profile.getTotalQueries()).append("次\n");
        
        // ⭐ 地点权重（按频率降序）
        Map<String, Object> additional = parseAdditionalPrefs(profile.getAdditionalPreferences());
        @SuppressWarnings("unchecked")
        Map<String, Integer> locationWeights = (Map<String, Integer>) additional.get("location_weights");
        if (locationWeights != null && !locationWeights.isEmpty()) {
            List<Map.Entry<String, Integer>> sortedLocs = locationWeights.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(3).toList();
            String locStr = sortedLocs.stream()
                    .map(e -> e.getKey() + "(" + getWeightLevel(e.getValue()) + ")")
                    .collect(Collectors.joining(", "));
            prompt.append("- 常用地点频率: ").append(locStr).append("\n");
        }
        
        // ⭐ 意图分布
        @SuppressWarnings("unchecked")
        Map<String, Integer> intentDist = (Map<String, Integer>) additional.get("intent_distribution");
        if (intentDist != null && !intentDist.isEmpty()) {
            String intentStr = intentDist.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(3)
                    .map(e -> e.getKey().replace("_", " ") + "(" + e.getValue() + "次)")
                    .collect(Collectors.joining(", "));
            prompt.append("- 常见查询: ").append(intentStr).append("\n");
        }
        
        prompt.append("\n请根据以上用户偏好，提供个性化的推荐。\n");
        
        return prompt.toString();
    }
    
    /**
     * ⭐ Phase 3: 构建带权重的偏好字符串
     * - 按权重降序排序
     * - 标注权重级别（高/中/低）
     * - 只取 Top-N
     * @param preferences 偏好数组
     * @param weights 权重Map
     * @return 格式化后的字符串，格式：辣(高), 川菜(中), 清淡(低)
     */
    private String buildWeightedPreferenceString(String[] preferences, Map<String, Integer> weights) {
        if (preferences == null || preferences.length == 0) {
            return "";
        }

        // Step 1: 按权重排序
        List<String> sorted = Arrays.stream(preferences)
                .filter(p -> p != null && !p.isBlank())
                .sorted((a, b) -> {
                    int weightA = weights.getOrDefault(a, 0);
                    int weightB = weights.getOrDefault(b, 0);
                    return Integer.compare(weightB, weightA); // 降序
                })
                .limit(TOP_PREFERENCES_LIMIT) // 只取 Top-N
                .toList();

        if (sorted.isEmpty()) {
            return "";
        }

        // Step 2: 格式化输出，标注权重级别
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) result.append(", ");
            String pref = sorted.get(i);
            int weight = weights.getOrDefault(pref, 0);
            String level = getWeightLevel(weight);
            result.append(pref).append("(").append(level).append(")");
        }

        log.debug("[UserProfile] 权重排序后的偏好: {}", result);
        return result.toString();
    }

    /**
     * ⭐ 根据权重值获取级别标签
     */
    private String getWeightLevel(int weight) {
        if (weight >= WEIGHT_LEVEL_HIGH) {
            return "高";
        } else if (weight >= WEIGHT_LEVEL_MEDIUM) {
            return "中";
        } else {
            return "低";
        }
    }
    
    /**
     * 提取特定类型的偏好
     */
    private Set<String> extractPreferences(String text, String prefix) {
        Set<String> preferences = new HashSet<>();
        
        for (Map.Entry<String, Pattern> entry : PREFERENCE_PATTERNS.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                Matcher matcher = entry.getValue().matcher(text);
                if (matcher.find()) {
                    // 提取匹配到的关键词
                    String keyword = matcher.group(1);
                    preferences.add(keyword);
                }
            }
        }
        
        return preferences;
    }
    
    /**
     * 提取预算信息
     */
    private String extractBudget(String text) {
        if (PREFERENCE_PATTERNS.get("budget_low").matcher(text).find()) {
            return "经济";
        } else if (PREFERENCE_PATTERNS.get("budget_high").matcher(text).find()) {
            return "高端";
        }
        return null;
    }
    
    /**
     * 转换 LLM 提取的预算格式
     */
    private String convertBudget(String llmBudget) {
        if (llmBudget == null) {
            return null;
        }
        return switch (llmBudget.toLowerCase()) {
            case "low" -> "经济";
            case "medium" -> "中等";
            case "high" -> "高端";
            default -> null;
        };
    }
    
    /**
     * 提取饮食限制
     */
    private List<String> extractDietaryRestrictions(String text) {
        List<String> restrictions = new ArrayList<>();
        
        if (PREFERENCE_PATTERNS.get("diet_vegetarian").matcher(text).find()) {
            restrictions.add("素食");
        }
        if (PREFERENCE_PATTERNS.get("diet_halal").matcher(text).find()) {
            restrictions.add("清真");
        }
        
        return restrictions;
    }
    
    /**
     * ⭐ 合并偏好并更新权重
     * @return 合并后的偏好数组
     */
    private String[] mergePreferencesWithWeight(String[] existing, Set<String> newPrefs, Map<String, Integer> weights) {
        if (newPrefs.isEmpty()) {
            return existing != null ? existing : new String[0];
        }

        Set<String> merged = new LinkedHashSet<>();
        if (existing != null) {
            merged.addAll(Arrays.asList(existing));
        }

        // 合并新偏好并更新权重
        for (String pref : newPrefs) {
            merged.add(pref);
            // ⭐ 核心：累加权重
            int currentWeight = weights.getOrDefault(pref, 0);
            int newWeight = currentWeight + WEIGHT_INCREMENT;
            weights.put(pref, Math.min(newWeight, WEIGHT_MAX));
            log.debug("[UserProfile] 偏好 '{}' 权重: {} → {}", pref, currentWeight, newWeight);
        }

        return merged.toArray(new String[0]);
    }
    
    /**
     * 添加到常用地点
     */
    private void addToPreferredLocations(UserProfile profile, String location) {
        Set<String> locations = new LinkedHashSet<>();
        String[] existing = profile.getPreferredLocationsArray();
        if (existing != null && existing.length > 0) {
            locations.addAll(Arrays.asList(existing));
        }
        locations.add(location);
        
        if (locations.size() > 10) {
            locations = new LinkedHashSet<>(locations.stream().skip(locations.size() - 10).toList());
        }
        
        profile.setPreferredLocationsArray(locations.toArray(new String[0]));
    }

    /**
     * ⭐ 更新意图分布（基于路由决策的 agent 名称，不使用硬编码关键词）
     */
    @Transactional
    public void updateIntentDistribution(Long userId, String routedAgent) {
        if (userId == null || routedAgent == null || routedAgent.isBlank()) return;
        try {
            UserProfile profile = userProfileMapper.findByUserId(userId);
            if (profile == null) return;
            
            Map<String, Object> additional = parseAdditionalPrefs(profile.getAdditionalPreferences());
            @SuppressWarnings("unchecked")
            Map<String, Integer> intentDist = (Map<String, Integer>) additional.getOrDefault("intent_distribution", new HashMap<String, Integer>());
            intentDist.put(routedAgent, intentDist.getOrDefault(routedAgent, 0) + 1);
            additional.put("intent_distribution", intentDist);
            profile.setAdditionalPreferences(toJsonObject(additional));
            
            if (profile.getId() != null) {
                profile.setUpdatedAt(LocalDateTime.now());
                userProfileMapper.updateProfile(profile);
            }
        } catch (Exception e) {
            log.warn("[UserProfile] 更新意图分布失败: {}", e.getMessage());
        }
    }

    /**
     * ⭐ 更新地点频率统计（记录每个地点被查询的次数）
     */
    private void updateLocationWeight(UserProfile profile, String location) {
        if (location == null || location.isBlank()) return;
        Map<String, Object> additional = parseAdditionalPrefs(profile.getAdditionalPreferences());
        
        @SuppressWarnings("unchecked")
        Map<String, Integer> locationWeights = (Map<String, Integer>) additional.getOrDefault("location_weights", new HashMap<String, Integer>());
        locationWeights.put(location, locationWeights.getOrDefault(location, 0) + 1);
        additional.put("location_weights", locationWeights);
        
        profile.setAdditionalPreferences(toJsonObject(additional));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAdditionalPrefs(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return new ObjectMapper().readValue(json, HashMap.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String toJsonObject(Map<String, Object> map) {
        try {
            return new ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
