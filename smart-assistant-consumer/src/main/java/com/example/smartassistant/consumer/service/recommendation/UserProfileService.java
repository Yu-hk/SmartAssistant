/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.service.recommendation;

import com.example.smartassistant.consumer.entity.UserProfile;
import com.example.smartassistant.consumer.service.memory.MemorySource;
import com.example.smartassistant.consumer.service.memory.MemoryVersionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
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
    private final MemoryVersionStore memoryVersionStore;

    public UserProfileService(LLMPreferenceExtractor llmExtractor, MemoryVersionStore memoryVersionStore) {
        this.llmExtractor = llmExtractor;
        this.memoryVersionStore = memoryVersionStore;
    }

    /** Initializes empty containers only; cold start must not invent user preferences. */
    private UserProfile applyDefaultProfile(UserProfile profile) {
        profile.setPreferenceGroupsMap(profile.getPreferenceGroupsMap());
        profile.setPreferenceWeightsMap(profile.getPreferenceWeightsMap());
        return profile;
    }

    // ==================== 写入 ====================

    /**
     * 从问题中提取偏好信息并更新用户画像
     */
    public void extractAndUpdatePreferences(Long userId, String question, String extractedLocation) {
        if (userId == null || question == null) return;

        try {
            UserProfile profile = loadProfile(userId);
            if (profile == null) {
                profile = new UserProfile();
                profile.setUserId(userId);
                profile.setTotalQueries(0);
                // ⭐ 新用户：注入群体画像冷启动默认值
                profile = applyDefaultProfile(profile);
                log.info("[UserProfile] 新用户冷启动，注入默认画像: userId={}", userId);
            }

            profile.setTotalQueries(profile.getTotalQueries() + 1);
            profile.setLastQueryAt(LocalDateTime.now());

            // LLM 提取偏好
            LLMPreferenceExtractor.ExtractedPreferences llmPrefs = llmExtractor.extract(question);

            String location = extractedLocation;
            if ((location == null || location.isBlank()) && llmPrefs.location() != null) {
                location = llmPrefs.location();
            }

            Map<String, List<String>> preferenceGroups = llmPrefs.preferenceGroups();
            List<String> negativePreferences = llmPrefs.negativePreferences();
            String budget = llmPrefs.budget();

            // 更新地点权重
            if (location != null && !location.isBlank()) {
                updateLocationWeight(profile, location);
            }

            mergePreferenceGroups(profile, preferenceGroups);
            if (negativePreferences != null && !negativePreferences.isEmpty()) {
                mergePreferenceGroups(profile, Map.of("negative", negativePreferences));
            }

            if (budget != null) profile.setBudgetRange(budget);

            // ⭐ P4-B 版本化记忆：关键偏好以「来源分级」写入版本化记忆库，
            //    用户改主意时按 优先级(EXPLICIT>FACT>INFERRED) + 时间 留存历史版本（不物理删除）
            if (memoryVersionStore != null) {
                preferenceGroups.forEach((group, values) -> values.forEach(value ->
                        recordMemory(userId, "PREFERENCE", group, value, MemorySource.INFERRED)));
                for (String value : negativePreferences) {
                    recordMemory(userId, "PREFERENCE_NEGATIVE", "negative", value,
                            MemorySource.EXPLICIT);
                }
                if (budget != null) recordMemory(userId, "BUDGET", "预算范围", budget, MemorySource.EXPLICIT);
                if (location != null) recordMemory(userId, "LOCATION", location, "常用地点", MemorySource.INFERRED);
            }

            saveProfile(profile);

        } catch (Exception e) {
            log.warn("[UserProfile] 更新偏好失败: userId={}, error={}", userId, e.getMessage());
        }
    }

    /**
     * 用户画像属于旁路增强，不能占用主对话链路的超时预算。
     */
    @Async("taskExecutor")
    public void extractAndUpdatePreferencesAsync(Long userId, String question, String extractedLocation) {
        extractAndUpdatePreferences(userId, question, extractedLocation);
    }

    // ==================== 读取 ====================

    /**
     * 构建用户画像 Prompt。
     * 若用户首次使用（无历史画像），自动应用冷启动默认值，确保 Prompt 中始终有基础个性化上下文。
     */
    public String buildUserProfilePrompt(Long userId) {
        if (userId == null) return "";
        UserProfile profile = loadProfile(userId);
        if (profile == null) {
            // 冷启动：用默认画像生成 Prompt，但不持久化（由 extractAndUpdatePreferences 写入）
            profile = applyDefaultProfile(new UserProfile());
            log.debug("[UserProfile] 冷启动 Prompt 生成: userId={}", userId);
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("【用户历史信息】\n");

        String[] prefLocs = profile.getPreferredLocationsArray();
        if (prefLocs != null && prefLocs.length > 0) {
            prompt.append("- 常用地点: ").append(String.join(", ", prefLocs)).append("\n");
        }

        Map<String, Integer> weights = profile.getPreferenceWeightsMap();
        collectPreferenceGroups(profile).forEach((group, values) -> {
            if (values != null && !values.isEmpty()) {
                prompt.append("- ").append(group).append(": ")
                        .append(buildWeightedPreferenceString(values.toArray(String[]::new), weights))
                        .append("\n");
            }
        });

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

    /**
     * ⭐ P4-B 版本化记忆写入（异常安全，失败仅记录调试日志不影响主流程）。
     */
    private void recordMemory(Long userId, String category, String key, String value, MemorySource source) {
        try {
            memoryVersionStore.add(userId, category, key, value, source);
        } catch (Exception e) {
            log.debug("[UserProfile] 版本化记忆写入跳过: userId={}, key={}, error={}", userId, key, e.getMessage());
        }
    }

    private void saveProfile(UserProfile profile) {        try {
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

    private void mergePreferenceGroups(UserProfile profile,
                                       Map<String, List<String>> newGroups) {
        if (newGroups == null || newGroups.isEmpty()) return;
        Map<String, List<String>> groups = new LinkedHashMap<>(profile.getPreferenceGroupsMap());
        Map<String, Integer> weights = profile.getPreferenceWeightsMap();
        newGroups.forEach((group, values) -> {
            if (group == null || group.isBlank() || values == null) return;
            LinkedHashSet<String> merged = new LinkedHashSet<>(
                    groups.getOrDefault(group, List.of()));
            values.stream().filter(value -> value != null && !value.isBlank()).forEach(value -> {
                merged.add(value);
                weights.merge(value, 1, Integer::sum);
            });
            if (!merged.isEmpty()) groups.put(group, List.copyOf(merged));
        });
        profile.setPreferenceGroupsMap(groups);
        profile.setPreferenceWeightsMap(weights);
    }

    /** Reads new generic groups and folds legacy files into compatibility groups. */
    private Map<String, List<String>> collectPreferenceGroups(UserProfile profile) {
        Map<String, List<String>> groups = new LinkedHashMap<>(profile.getPreferenceGroupsMap());
        addLegacyGroup(groups, "legacy.food", profile.getFoodPreferencesArray());
        addLegacyGroup(groups, "legacy.travel", profile.getTravelPreferencesArray());
        addLegacyGroup(groups, "legacy.dietary", profile.getDietaryRestrictionsArray());
        return groups;
    }

    private void addLegacyGroup(Map<String, List<String>> groups, String name, String[] values) {
        if (values != null && values.length > 0) groups.putIfAbsent(name, List.of(values));
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
