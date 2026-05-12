/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.consumer.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

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
}
