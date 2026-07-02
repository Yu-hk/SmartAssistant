/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户画像（文件存储版，不再是 MyBatis 实体）
 * 存入 data/users/{userId}/preferences.json
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private Long id;
    private Long userId;

    private String[] preferredLocations;
    private String[] foodPreferences;
    private String[] travelPreferences;
    private String[] dietaryRestrictions;
    private String budgetRange;

    /** 偏好权重，如 {"川菜": 10, "火锅": 8} */
    private Map<String, Integer> preferenceWeights = new HashMap<>();

    /** 意图分布，如 {"FOOD": 15, "TRAVEL": 8} */
    private Map<String, Integer> intentDistribution = new HashMap<>();

    /** 附加偏好（JSON 对象） */
    private String additionalPreferences;

    private Integer totalQueries = 0;
    private LocalDateTime lastQueryAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ========== JSON 数组兼容方法 ==========

    public String[] getPreferredLocationsArray() { return preferredLocations; }
    public void setPreferredLocationsArray(String[] arr) { this.preferredLocations = arr; }

    public String[] getFoodPreferencesArray() { return foodPreferences; }
    public void setFoodPreferencesArray(String[] arr) { this.foodPreferences = arr; }

    public String[] getTravelPreferencesArray() { return travelPreferences; }
    public void setTravelPreferencesArray(String[] arr) { this.travelPreferences = arr; }

    public String[] getDietaryRestrictionsArray() { return dietaryRestrictions; }
    public void setDietaryRestrictionsArray(String[] arr) { this.dietaryRestrictions = arr; }

    public Map<String, Integer> getPreferenceWeightsMap() {
        return preferenceWeights != null ? preferenceWeights : new HashMap<>();
    }

    public void setPreferenceWeightsMap(Map<String, Integer> weights) {
        this.preferenceWeights = weights != null ? weights : new HashMap<>();
    }

    public Map<String, Integer> getIntentDistribution() {
        return intentDistribution != null ? intentDistribution : new HashMap<>();
    }

    public void setIntentDistribution(Map<String, Integer> distribution) {
        this.intentDistribution = distribution != null ? distribution : new HashMap<>();
    }

    // ==================== 时间衰减（P1 新增） ====================

    /**
     * 距上次更新的天数。
     * 用于计算时间衰减，从未更新过则视为 365 天（极度衰减）。
     */
    @JsonIgnore
    public long daysSinceLastUpdate() {
        if (updatedAt == null) return 365L;
        return ChronoUnit.DAYS.between(updatedAt.toLocalDate(), LocalDate.now());
    }

    /**
     * 获取经时间衰减后的偏好权重。
     * <p>
     * 衰减公式：{@code weight * e^(-lambda * daysSinceLastUpdate)}<br>
     * 最小值 clamp 到 0.1，确保旧偏好不会完全归零。
     * </p>
     *
     * @param lambda 衰减系数，默认推荐 0.01（约每天衰减 1%，30 天后剩 74%）
     * @return key → 衰减后权重
     */
    @JsonIgnore
    public Map<String, Double> getDecayedPreferenceWeights(double lambda) {
        if (preferenceWeights == null || preferenceWeights.isEmpty()) return Map.of();

        long days = daysSinceLastUpdate();
        if (days <= 0) {
            // 今天刚更新，不需要衰减
            return preferenceWeights.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> (double) e.getValue()));
        }

        double decayFactor = Math.exp(-lambda * days);
        return preferenceWeights.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> Math.max(0.1, e.getValue() * decayFactor)));
    }

    /**
     * 快捷方法：使用默认衰减系数 0.01 计算偏好权重衰减。
     */
    @JsonIgnore
    public Map<String, Double> getDecayedPreferenceWeights() {
        return getDecayedPreferenceWeights(0.01);
    }

    /**
     * 获取经时间衰减后的意图分布。
     * 与偏好权重使用相同的衰减公式。
     *
     * @param lambda 衰减系数
     * @return key → 衰减后分布
     */
    @JsonIgnore
    public Map<String, Double> getDecayedIntentDistribution(double lambda) {
        if (intentDistribution == null || intentDistribution.isEmpty()) return Map.of();

        long days = daysSinceLastUpdate();
        if (days <= 0) {
            return intentDistribution.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> (double) e.getValue()));
        }

        double decayFactor = Math.exp(-lambda * days);
        return intentDistribution.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> Math.max(0.1, e.getValue() * decayFactor)));
    }

    /**
     * 快捷方法：使用默认衰减系数 0.01 计算意图分布衰减。
     */
    @JsonIgnore
    public Map<String, Double> getDecayedIntentDistribution() {
        return getDecayedIntentDistribution(0.01);
    }
}
