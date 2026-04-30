package com.example.smartassistant.consumer.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户偏好向量实体
 * 用于存储用户偏好数据的向量化表示，支持语义相似度检索
 *
 * @author SmartAssistant
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_preference_vectors")
public class UserPreferenceVector {

    /**
     * 向量类型枚举
     */
    public enum VectorType {
        /** 美食偏好 */
        FOOD("food", "美食偏好"),
        /** 旅行偏好 */
        TRAVEL("travel", "旅行偏好"),
        /** 饮食限制 */
        DIETARY("dietary", "饮食限制"),
        /** 综合偏好（所有偏好合并） */
        ALL("all", "综合偏好");

        private final String code;
        private final String description;

        VectorType(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }

        public static VectorType fromCode(String code) {
            for (VectorType type : values()) {
                if (type.code.equals(code)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown vector type: " + code);
        }
    }

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID（关联 user_profiles.id）
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 向量类型：food, travel, dietary, all
     */
    @TableField("vector_type")
    private String vectorType;

    /**
     * 原始文本内容（用于生成向量）
     */
    @TableField("content")
    private String content;

    /**
     * 唯一标识：userId_vectorType
     */
    @TableField("embedding_id")
    private String embeddingId;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 生成唯一 embeddingId
     */
    public static String generateEmbeddingId(Long userId, String vectorType) {
        return userId + "_" + vectorType;
    }

    /**
     * 构建美食偏好向量
     */
    public static UserPreferenceVector buildFoodVector(Long userId, String[] foodPreferences) {
        String content = foodPreferences != null && foodPreferences.length > 0
            ? String.join("、", foodPreferences)
            : "";
        return UserPreferenceVector.builder()
                .userId(userId)
                .vectorType(VectorType.FOOD.getCode())
                .content(content)
                .embeddingId(generateEmbeddingId(userId, VectorType.FOOD.getCode()))
                .build();
    }

    /**
     * 构建旅行偏好向量
     */
    public static UserPreferenceVector buildTravelVector(Long userId, String[] travelPreferences) {
        String content = travelPreferences != null && travelPreferences.length > 0
            ? String.join("、", travelPreferences)
            : "";
        return UserPreferenceVector.builder()
                .userId(userId)
                .vectorType(VectorType.TRAVEL.getCode())
                .content(content)
                .embeddingId(generateEmbeddingId(userId, VectorType.TRAVEL.getCode()))
                .build();
    }

    /**
     * 构建饮食限制向量
     */
    public static UserPreferenceVector buildDietaryVector(Long userId, String[] dietaryRestrictions) {
        String content = dietaryRestrictions != null && dietaryRestrictions.length > 0
            ? String.join("、", dietaryRestrictions)
            : "";
        return UserPreferenceVector.builder()
                .userId(userId)
                .vectorType(VectorType.DIETARY.getCode())
                .content(content)
                .embeddingId(generateEmbeddingId(userId, VectorType.DIETARY.getCode()))
                .build();
    }

    /**
     * 构建综合偏好向量
     */
    public static UserPreferenceVector buildAllVector(Long userId, String[] foodPreferences,
            String[] travelPreferences, String[] dietaryRestrictions, String budgetRange) {
        StringBuilder sb = new StringBuilder();

        if (foodPreferences != null && foodPreferences.length > 0) {
            sb.append("我喜欢：").append(String.join("、", foodPreferences)).append("。");
        }
        if (travelPreferences != null && travelPreferences.length > 0) {
            sb.append("旅行偏好：").append(String.join("、", travelPreferences)).append("。");
        }
        if (dietaryRestrictions != null && dietaryRestrictions.length > 0) {
            sb.append("饮食限制：").append(String.join("、", dietaryRestrictions)).append("。");
        }
        if (budgetRange != null && !budgetRange.isBlank()) {
            sb.append("预算范围：").append(budgetRange).append("。");
        }

        String content = sb.toString();
        return UserPreferenceVector.builder()
                .userId(userId)
                .vectorType(VectorType.ALL.getCode())
                .content(content)
                .embeddingId(generateEmbeddingId(userId, VectorType.ALL.getCode()))
                .build();
    }
}
