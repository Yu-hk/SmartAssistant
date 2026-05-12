/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.consumer.entity.UserProfile;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 用户画像服务（文件存储版）
 * 偏好存入 data/users/{userId}/preferences.json
 */
@Service
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Value("${app.data.dir:data/users}")
    private String basePath;

    private final LLMPreferenceExtractor llmExtractor;

    // 偏好关键词模式
    private static final Map<String, Pattern> PREFERENCE_PATTERNS = new HashMap<>();

    static {
        PREFERENCE_PATTERNS.put("food_spicy", Pattern.compile("(辣|麻辣|香辣|酸辣|微辣)"));
        PREFERENCE_PATTERNS.put("food_light", Pattern.compile("(清淡|清蒸|少油|少盐)"));
        PREFERENCE_PATTERNS.put("food_sweet", Pattern.compile("(甜|甜品|糖水)"));
        PREFERENCE_PATTERNS.put("travel_nature", Pattern.compile("(自然|山水|风景|户外|徒步)"));
        PREFERENCE_PATTERNS.put("travel_culture", Pattern.compile("(文化|历史|古迹|博物馆)"));
        PREFERENCE_PATTERNS.put("travel_relax", Pattern.compile("(休闲|度假|放松|温泉)"));
        PREFERENCE_PATTERNS.put("budget_low", Pattern.compile("(便宜|经济|实惠|性价比)"));
        PREFERENCE_PATTERNS.put("budget_high", Pattern.compile("(高端|豪华|精品|五星)"));
        PREFERENCE_PATTERNS.put("diet_vegetarian", Pattern.compile("(素食|素菜|不吃肉)"));
        PREFERENCE_PATTERNS.put("diet_halal", Pattern.compile("(清真|回族)"));
    }

    public UserProfileService(LLMPreferenceExtractor llmExtractor) {
        this.llmExtractor = llmExtractor;
    }

    // ==================== 写入 ====================

    /**
     * 从问题中提取偏好信息并更新用户画像
     */
    @Transactional
    public void extractAndUpdatePreferences(Long userId, String question, String extractedLocation) {
        if (userId == null || question == null) return;

        try {
            UserProfile profile = loadProfile(userId);
            if (profile == null) {
                profile = new UserProfile();
                profile.setUserId(userId);
                profile.setTotalQueries(0);
            }

            profile.setTotalQueries(profile.getTotalQueries() + 1);
            profile.setLastQueryAt(LocalDateTime.now());

            // LLM 提取偏好
            LLMPreferenceExtractor.ExtractedPreferences llmPrefs = llmExtractor.extract(question);

            String location = extractedLocation;
            if ((location == null || location.isBlank()) && llmPrefs.getLocation() != null) {
                location = llmPrefs.getLocation();
            }

            Set<String> foodPrefs = new HashSet<>(llmPrefs.getFoodPreferences());
            Set<String> travelPrefs = new HashSet<>(llmPrefs.getTravelPreferences());
            String budget = convertBudget(llmPrefs.getBudget());
            List<String> dietaryRestrictions = llmPrefs.getDietaryRestrictions();

            if (foodPrefs.isEmpty()) foodPrefs = extractPreferences(question, "food_");
            if (travelPrefs.isEmpty()) travelPrefs = extractPreferences(question, "travel_");
            if (budget == null) budget = extractBudget(question);
            if (dietaryRestrictions.isEmpty()) dietaryRestrictions = extractDietaryRestrictions(question);

            // 更新地点权重
            if (location != null && !location.isBlank()) {
                updateLocationWeight(profile, location);
            }

            // 合并美食偏好带权重
            mergePreferences(profile, foodPrefs, "food");

            // 合并旅行偏好
            Set<String> travelSet = new HashSet<>(travelPrefs);
            if (profile.getTravelPreferencesArray() != null) {
                travelSet.addAll(Arrays.asList(profile.getTravelPreferencesArray()));
            }
            profile.setTravelPreferencesArray(travelSet.toArray(new String[0]));

            if (budget != null) profile.setBudgetRange(budget);
            if (!dietaryRestrictions.isEmpty()) {
                Set<String> dietSet = new HashSet<>(dietaryRestrictions);
                if (profile.getDietaryRestrictionsArray() != null) {
                    dietSet.addAll(Arrays.asList(profile.getDietaryRestrictionsArray()));
                }
                profile.setDietaryRestrictionsArray(dietSet.toArray(new String[0]));
            }

            saveProfile(profile);

        } catch (Exception e) {
            log.warn("[UserProfile] 更新偏好失败: userId={}, error={}", userId, e.getMessage());
        }
    }

    // ==================== 读取 ====================

    /**
     * 构建用户画像 Prompt
     */
    public String buildUserProfilePrompt(Long userId) {
        if (userId == null) return "";
        UserProfile profile = loadProfile(userId);
        if (profile == null) return "";

        StringBuilder prompt = new StringBuilder();
        prompt.append("【用户历史信息】\n");

        String[] prefLocs = profile.getPreferredLocationsArray();
        if (prefLocs != null && prefLocs.length > 0) {
            prompt.append("- 常用地点: ").append(String.join(", ", prefLocs)).append("\n");
        }

        String[] foodPrefs = profile.getFoodPreferencesArray();
        if (foodPrefs != null && foodPrefs.length > 0) {
            Map<String, Integer> weights = profile.getPreferenceWeightsMap();
            prompt.append("- 美食偏好: ").append(buildWeightedPreferenceString(foodPrefs, weights)).append("\n");
        }

        String[] travelPrefs = profile.getTravelPreferencesArray();
        if (travelPrefs != null && travelPrefs.length > 0) {
            Map<String, Integer> weights = profile.getPreferenceWeightsMap();
            prompt.append("- 旅行偏好: ").append(buildWeightedPreferenceString(travelPrefs, weights)).append("\n");
        }

        String[] dietRes = profile.getDietaryRestrictionsArray();
        if (dietRes != null && dietRes.length > 0) {
            prompt.append("- 饮食限制: ").append(String.join(", ", dietRes)).append("\n");
        }

        if (profile.getBudgetRange() != null) {
            prompt.append("- 预算范围: ").append(profile.getBudgetRange()).append("\n");
        }
        prompt.append("- 历史查询: ").append(profile.getTotalQueries()).append("次\n");

        return prompt.toString();
    }

    /**
     * 获取用户画像（给其他服务使用）
     */
    public UserProfile getProfile(Long userId) {
        return loadProfile(userId);
    }

    /**
     * 更新意图分布
     */
    public void updateIntentDistribution(Long userId, String routedAgent) {
        if (userId == null || routedAgent == null) return;

        try {
            UserProfile profile = loadProfile(userId);
            if (profile == null) return;

            String intentTag = routedAgent.replace("_chat", "").replace("_", "");
            Map<String, Integer> distribution = profile.getIntentDistribution();
            distribution.merge(intentTag, 1, Integer::sum);
            profile.setIntentDistribution(distribution);

            saveProfile(profile);

        } catch (Exception e) {
            log.warn("[UserProfile] 更新意图分布失败: {}", e.getMessage());
        }
    }

    // ==================== 文件 I/O ====================

    private Path profilePath(Long userId) {
        return Paths.get(basePath, String.valueOf(userId), "preferences.json");
    }

    private UserProfile loadProfile(Long userId) {
        Path path = profilePath(userId);
        if (!Files.exists(path)) return null;
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            return objectMapper.readValue(json, UserProfile.class);
        } catch (IOException e) {
            log.warn("[UserProfile] 加载失败: userId={}, error={}", userId, e.getMessage());
            return null;
        }
    }

    private void saveProfile(UserProfile profile) {
        try {
            Path dir = Paths.get(basePath, String.valueOf(profile.getUserId()));
            Files.createDirectories(dir);
            profile.setUpdatedAt(LocalDateTime.now());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(profile);
            Files.writeString(profilePath(profile.getUserId()), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[UserProfile] 保存失败: userId={}, error={}", profile.getUserId(), e.getMessage());
        }
    }

    // ==================== 偏好提取 ====================

    private void mergePreferences(UserProfile profile, Set<String> newPrefs, String type) {
        Set<String> merged = new HashSet<>(newPrefs);
        String[] existing = "food".equals(type)
                ? profile.getFoodPreferencesArray()
                : profile.getTravelPreferencesArray();
        if (existing != null) merged.addAll(Arrays.asList(existing));
        if ("food".equals(type)) {
            profile.setFoodPreferencesArray(merged.toArray(new String[0]));
        } else {
            profile.setTravelPreferencesArray(merged.toArray(new String[0]));
        }

        // 加权
        Map<String, Integer> weights = profile.getPreferenceWeightsMap();
        for (String pref : newPrefs) {
            weights.merge(pref, 1, Integer::sum);
        }
        profile.setPreferenceWeightsMap(weights);
    }

    private void updateLocationWeight(UserProfile profile, String location) {
        Map<String, Object> additional = parseAdditionalPrefs(profile.getAdditionalPreferences());
        @SuppressWarnings("unchecked")
        Map<String, Integer> locationWeights = (Map<String, Integer>) additional.computeIfAbsent("location_weights", k -> new HashMap<String, Integer>());
        locationWeights.merge(location, 1, Integer::sum);
        try {
            profile.setAdditionalPreferences(objectMapper.writeValueAsString(additional));
        } catch (IOException e) {
            log.warn("[UserProfile] 序列化附加偏好失败: {}", e.getMessage());
        }
    }

    private Set<String> extractPreferences(String text, String prefix) {
        Set<String> prefs = new HashSet<>();
        for (Map.Entry<String, Pattern> entry : PREFERENCE_PATTERNS.entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue().matcher(text).find()) {
                String label = switch (entry.getKey()) {
                    case "food_spicy" -> "辣";
                    case "food_light" -> "清淡";
                    case "food_sweet" -> "甜";
                    case "travel_nature" -> "自然风光";
                    case "travel_culture" -> "人文历史";
                    case "travel_relax" -> "休闲度假";
                    default -> entry.getKey().replace(prefix, "");
                };
                prefs.add(label);
            }
        }
        return prefs;
    }

    private String extractBudget(String text) {
        for (Map.Entry<String, Pattern> entry : PREFERENCE_PATTERNS.entrySet()) {
            if (entry.getKey().startsWith("budget_") && entry.getValue().matcher(text).find()) {
                return entry.getKey().equals("budget_low") ? "经济实惠" : "高端消费";
            }
        }
        return null;
    }

    private List<String> extractDietaryRestrictions(String text) {
        List<String> restrictions = new ArrayList<>();
        for (Map.Entry<String, Pattern> entry : PREFERENCE_PATTERNS.entrySet()) {
            if (entry.getKey().startsWith("diet_") && entry.getValue().matcher(text).find()) {
                restrictions.add(entry.getKey().equals("diet_vegetarian") ? "素食" : "清真");
            }
        }
        return restrictions;
    }

    private String convertBudget(String llmBudget) {
        if (llmBudget == null) return null;
        return switch (llmBudget.toLowerCase()) {
            case "low", "cheap", "经济" -> "经济实惠";
            case "medium", "中等" -> "中等";
            case "high", "luxury", "高端" -> "高端消费";
            default -> llmBudget;
        };
    }

    private String buildWeightedPreferenceString(String[] prefs, Map<String, Integer> weights) {
        return Arrays.stream(prefs)
                .map(p -> p + "(" + weights.getOrDefault(p, 1) + ")")
                .collect(Collectors.joining(", "));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAdditionalPrefs(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, HashMap.class);
        } catch (IOException e) {
            return new HashMap<>();
        }
    }
}
