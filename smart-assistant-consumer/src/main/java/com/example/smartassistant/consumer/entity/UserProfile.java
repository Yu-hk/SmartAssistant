package com.example.smartassistant.consumer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户画像实体 (MyBatis Plus)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_profiles")
public class UserProfile {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    @TableField("user_id")
    private Long userId;
    
    // 地点偏好（存储为JSON字符串）
    @TableField("preferred_locations")
    private String preferredLocations;
    
    // 美食偏好（存储为JSON字符串）
    @TableField("food_preferences")
    private String foodPreferences;
    
    // 旅行偏好（存储为JSON字符串）
    @TableField("travel_preferences")
    private String travelPreferences;
    
    // 饮食限制（存储为JSON字符串）
    @TableField("dietary_restrictions")
    private String dietaryRestrictions;
    
    // 预算范围
    @TableField("budget_range")
    private String budgetRange;

    // ⭐ 偏好权重（JSON格式，存储 Map<String, Integer>）
    // 格式: {"川菜": 10, "火锅": 8, "辣": 5, "山水": 7}
    @TableField("preference_weights")
    private String preferenceWeights;

    // 其他偏好（JSON）
    @TableField("additional_preferences")
    private String additionalPreferences;
    
    // 统计信息
    @TableField("total_queries")
    private Integer totalQueries = 0;
    
    @TableField("last_query_at")
    private LocalDateTime lastQueryAt;
    
    @TableField("created_at")
    private LocalDateTime createdAt;
    
    @TableField("updated_at")
    private LocalDateTime updatedAt;
    
    // ========== 便捷方法 ==========
    
    public String[] getPreferredLocationsArray() {
        return parseJsonArray(preferredLocations);
    }
    
    public void setPreferredLocationsArray(String[] arr) {
        this.preferredLocations = toJsonArray(arr);
    }
    
    public String[] getFoodPreferencesArray() {
        return parseJsonArray(foodPreferences);
    }
    
    public void setFoodPreferencesArray(String[] arr) {
        this.foodPreferences = toJsonArray(arr);
    }
    
    public String[] getTravelPreferencesArray() {
        return parseJsonArray(travelPreferences);
    }
    
    public void setTravelPreferencesArray(String[] arr) {
        this.travelPreferences = toJsonArray(arr);
    }
    
    public String[] getDietaryRestrictionsArray() {
        return parseJsonArray(dietaryRestrictions);
    }
    
    public void setDietaryRestrictionsArray(String[] arr) {
        this.dietaryRestrictions = toJsonArray(arr);
    }

    /**
     * ⭐ 获取偏好权重Map
     */
    public Map<String, Integer> getPreferenceWeightsMap() {
        if (preferenceWeights == null || preferenceWeights.isEmpty()) {
            return new HashMap<>();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Integer> result = objectMapper.readValue(preferenceWeights,
                    objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, Integer.class));
            return result;
        } catch (JsonProcessingException e) {
            return new HashMap<>();
        }
    }

    /**
     * ⭐ 设置偏好权重Map
     */
    public void setPreferenceWeightsMap(Map<String, Integer> weights) {
        if (weights == null || weights.isEmpty()) {
            this.preferenceWeights = "{}";
            return;
        }
        try {
            this.preferenceWeights = objectMapper.writeValueAsString(weights);
        } catch (JsonProcessingException e) {
            this.preferenceWeights = "{}";
        }
    }

    private String[] parseJsonArray(String json) {
        if (json == null || json.isEmpty()) {
            return new String[0];
        }
        try {
            return objectMapper.readValue(json, String[].class);
        } catch (JsonProcessingException e) {
            return new String[0];
        }
    }
    
    private String toJsonArray(String[] arr) {
        if (arr == null || arr.length == 0) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(arr);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
