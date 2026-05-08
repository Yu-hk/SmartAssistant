package com.example.smartassistant.consumer.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 用户对话文档实体
 * 每次有价值的对话沉淀为用户文档，存入 pgvector，供后续 RAG 检索
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_conversation_docs")
public class UserConversationDoc {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    @TableField("user_id")
    private Long userId;

    /** 会话ID */
    @TableField("session_id")
    private String sessionId;

    /** 对话内容（一问一答原文） */
    @TableField("content")
    private String content;

    /** 路由到的 Agent 名称 */
    @TableField("agent_name")
    private String agentName;

    /** 意图标签 */
    @TableField("intent_tag")
    private String intentTag;

    /** 对话轮数（该会话的累计轮次） */
    @TableField("turn_count")
    private Integer turnCount;

    /** 创建时间 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 向量嵌入（由 content 生成，1024维 text-embedding-v4） */
    @TableField("embedding")
    private float[] embedding;
}
