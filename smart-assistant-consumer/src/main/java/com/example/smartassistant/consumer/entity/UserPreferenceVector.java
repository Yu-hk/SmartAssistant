package com.example.smartassistant.consumer.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

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
    @Getter
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

}
