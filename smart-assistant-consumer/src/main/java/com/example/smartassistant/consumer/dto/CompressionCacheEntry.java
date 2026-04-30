package com.example.smartassistant.consumer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对话压缩结果缓存条目
 * 存储在 Redis 中，用于避免对同一 session 的重复压缩计算
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompressionCacheEntry {

    /**
     * 压缩后的对话历史
     */
    private List<StructuredPrompt.ConversationMessage> compressedHistory;

    /**
     * 压缩时基于的原始消息条数（用于判断缓存是否过期）
     */
    private Integer originalMessageCount;

    /**
     * 压缩时基于的原始轮数
     */
    private Integer originalRoundCount;

    /**
     * 使用的压缩策略
     */
    private String strategy;

    /**
     * 估算的 token 数
     */
    private Integer estimatedTokens;

    /**
     * 缓存创建时间戳
     */
    private Long timestamp;

    /**
     * 是否已压缩
     */
    private Boolean compressed;

    /**
     * 压缩截至的消息 ID（用于增量压缩）
     * 下次查询时，只需查询此 ID 之后的新消息
     */
    private Long lastCompressedMessageId;
}
