package com.example.smartassistant.consumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 聊天请求排队配置
 * <p>
 * 控制 LLM 请求的并发槽位数和排队行为。
 * max-concurrent 基于压测数据：5 并发时 P50≈4.4s，10 并发时 P50≈8.8s
 */
@Configuration
@ConfigurationProperties(prefix = "chat.queue")
public class ChatQueueConfig {

    /** 最大并发 LLM 请求数 */
    private int maxConcurrent = 5;

    /** 最大排队长度 */
    private int maxQueueSize = 50;

    /** 排队等待超时（毫秒），超过自动取消 */
    private long queueTimeoutMs = 60000;

    /** 单次请求最长执行时间（毫秒） */
    private long slotTimeoutMs = 120000;

    /** 排队位置广播间隔（毫秒） */
    private long positionBroadcastIntervalMs = 5000;

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    public void setMaxQueueSize(int maxQueueSize) {
        this.maxQueueSize = maxQueueSize;
    }

    public long getQueueTimeoutMs() {
        return queueTimeoutMs;
    }

    public void setQueueTimeoutMs(long queueTimeoutMs) {
        this.queueTimeoutMs = queueTimeoutMs;
    }

    public long getSlotTimeoutMs() {
        return slotTimeoutMs;
    }

    public void setSlotTimeoutMs(long slotTimeoutMs) {
        this.slotTimeoutMs = slotTimeoutMs;
    }

    public long getPositionBroadcastIntervalMs() {
        return positionBroadcastIntervalMs;
    }

    public void setPositionBroadcastIntervalMs(long positionBroadcastIntervalMs) {
        this.positionBroadcastIntervalMs = positionBroadcastIntervalMs;
    }
}
