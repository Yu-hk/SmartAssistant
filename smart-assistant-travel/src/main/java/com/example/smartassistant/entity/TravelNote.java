package com.example.smartassistant.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 用户游记/攻略实体
 * 存储用户上传的旅行攻略，支持 RAG 向量检索
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("user_travel_notes")
public class TravelNote {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 攻略标题
     */
    private String title;

    /**
     * 原始文本内容
     */
    private String content;

    /**
     * 来源类型：text/pdf/image/external
     */
    private String sourceType;

    /**
     * 外部来源 URL（当 sourceType=external 时使用）
     */
    private String sourceUrl;

    /**
     * 主要目的地
     */
    private String location;

    /**
     * 标签（逗号分隔字符串）
     */
    private String tags;

    /**
     * 状态：active/deleted
     */
    private String status;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 虚拟游记（用于 RAG 降级场景，将分块内容聚合成一个"虚拟"游记）
     */
    @Data
    @NoArgsConstructor
    @lombok.EqualsAndHashCode(callSuper = false)
    public static class VirtualTravelNote extends TravelNote {
        public VirtualTravelNote(String title, String location, String content) {
            setTitle(title);
            setLocation(location);
            setContent(content);
        }
    }
}
