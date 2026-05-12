/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

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
 * 旅游景点实体类 (MyBatis Plus)
 * 用于持久化存储景点信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("tourist_attractions")
public class TouristAttraction {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("name")
    private String name;

    @TableField("city")
    private String city;

    @TableField("province")
    private String province;

    @TableField("description")
    private String description;

    @TableField("level")
    private String level; // 5A, 4A, etc.

    @TableField("ticket_price")
    private Double ticketPrice;

    @TableField("open_time")
    private String openTime;

    @TableField("suggest_duration")
    private Integer suggestDuration; // minutes

    // ⚠️ tags 和 highlights 是单独的关联表，不在主表中
    // 如果需要查询，使用 attraction_tags 和 attraction_highlights 表
    @TableField(exist = false)
    private List<String> tags;

    @TableField(exist = false)
    private List<String> highlights;

    @TableField("latitude")
    private Double latitude;

    @TableField("longitude")
    private Double longitude;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
