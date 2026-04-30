package com.example.smartassistant.consumer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对话上下文压缩配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "conversation.compression")
public class ConversationCompressionProperties {

    /** 是否启用压缩 */
    private boolean enabled = true;

    /** 触发压缩的最大轮数（超过此值开始压缩） */
    private int maxRoundsBeforeCompress = 10;

    /** 触发压缩的最大字符数 */
    private int maxCharsBeforeCompress = 2000;

    /** 触发压缩的最大估算 Token 数 */
    private int maxTokensBeforeCompress = 1500;

    /** 压缩后保留的最近轮数 */
    private int keepRecentRounds = 5;

    /** 压缩策略：truncate / extract / llm / auto */
    private String strategy = "auto";

    /** LLM 摘要的最大字数 */
    private int llmSummaryMaxChars = 200;

    /** 关键信息提取时保留的最大轮数 */
    private int extractMaxRounds = 10;

    /** Token 估算系数（中文字符平均 token 数，保守估计） */
    private double tokenEstimateRatio = 0.8;

    // ===== 缓存配置 =====

    /** 是否启用压缩结果缓存 */
    private boolean cacheEnabled = true;

    /** 缓存 TTL（分钟） */
    private int cacheTtlMinutes = 30;
}
