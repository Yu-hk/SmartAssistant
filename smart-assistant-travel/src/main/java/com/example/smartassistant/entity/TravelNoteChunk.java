package com.example.smartassistant.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 攻略分块实体
 * 存储分块后的攻略文本及其向量表示
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("travel_note_chunks")
public class TravelNoteChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联的游记 ID
     */
    private Long noteId;

    /**
     * 分块后的文本内容（通常 200-500 字）
     */
    private String chunkText;

    /**
     * 块序号
     */
    private Integer chunkIndex;

    /**
     * 向量（存储为 JSON 数组字符串，pgvector 会解析）
     * 注意：实际类型为 FLOAT4[]，MyBatis-Plus 通过 TypeHandler 处理
     */
    private float[] embedding;

    /**
     * 提取的地点关键词（用于精确过滤）
     * PostgreSQL 存储为 text[]，MyBatis 用 String 转换
     */
    private String locationKeywordsStr;

    /**
     * 内容类型：scenic(景点) / food(美食) / accommodation(住宿) / transport(交通) / general(通用)
     * ⭐ 在分块时由 classifyChunk() 自动判定并写入
     */
    private String contentType;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    // ========== 非数据库字段 ==========

    /**
     * 相似度分数（检索时计算）
     */
    @TableField(exist = false)
    private Double similarity;

    /**
     * 关联的游记标题（检索时 JOIN）
     */
    @TableField(exist = false)
    private String noteTitle;
}
