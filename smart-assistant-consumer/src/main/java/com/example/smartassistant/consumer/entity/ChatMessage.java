package com.example.smartassistant.consumer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 聊天消息实体类 (MyBatis Plus)
 * 用于持久化存储对话历史
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_messages")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("is_user")
    private Boolean isUser; // true=用户消息, false=AI回复

    @TableField("content")
    private String content;

    @TableField("target_agent")
    private String targetAgent;

    @TableField("turn_count")
    private Integer turnCount;

    @TableField("metadata")
    private String metadata; // JSON格式的额外元数据

    @TableField("created_at")
    private LocalDateTime createdAt;
}
