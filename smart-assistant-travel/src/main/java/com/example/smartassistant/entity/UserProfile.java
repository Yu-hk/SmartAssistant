package com.example.smartassistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户画像实体类 (MyBatis Plus)
 * 存储用户偏好和行为数据
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_profiles")
public class UserProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId; // 用户ID

    @TableField("username")
    private String username; // 用户名

    @TableField(value = "preferred_cities", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<String> preferredCities; // 偏好的城市

    @TableField(value = "preferred_tags", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<String> preferredTags; // 偏好的标签（历史、自然、美食等）

    @TableField("preferred_budget_min")
    private Double preferredBudgetMin; // 预算范围-最低

    @TableField("preferred_budget_max")
    private Double preferredBudgetMax; // 预算范围-最高

    @TableField(value = "preferred_levels", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<String> preferredLevels; // 偏好的景区等级（5A, 4A等）

    @TableField("preferred_duration")
    private Integer preferredDuration; // 偏好的游玩时长（分钟）

    @TableField(value = "viewed_attractions", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<String> viewedAttractions; // 浏览过的景点

    @TableField(value = "favorited_attractions", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<String> favoritedAttractions; // 收藏的景点

    @TableField("total_views")
    private Integer totalViews; // 总浏览次数

    @TableField("total_favorites")
    private Integer totalFavorites; // 总收藏数

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
