package com.example.smartassistant.consumer.service.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prompt 监控服务
 * 用于监控 JSON Prompt 的构建和传输指标
 */
@Service
public class PromptMonitoringService {
    
    private static final Logger log = LoggerFactory.getLogger(PromptMonitoringService.class);
    
    // 监控指标
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong totalJsonSize = new AtomicLong(0);
    private final AtomicLong parseFailures = new AtomicLong(0);
    private final AtomicLong fallbackToText = new AtomicLong(0);
    
    // JSON 大小分布统计
    private final Map<String, AtomicLong> sizeDistribution = new ConcurrentHashMap<>();
    
    /**
     * 记录请求
     */
    public void recordRequest(long jsonSize) {
        totalRequests.incrementAndGet();
        totalJsonSize.addAndGet(jsonSize);
        
        // 记录大小分布
        String bucket = getSizeBucket(jsonSize);
        sizeDistribution.computeIfAbsent(bucket, k -> new AtomicLong(0)).incrementAndGet();
        
        log.debug("[PromptMonitor] 请求记录: size={} bytes, bucket={}", jsonSize, bucket);
    }
    
    /**
     * 记录解析失败
     */
    public void recordParseFailure() {
        parseFailures.incrementAndGet();
        log.warn("[PromptMonitor] JSON 解析失败");
    }
    
    /**
     * 记录降级到文本格式
     */
    public void recordFallbackToText() {
        fallbackToText.incrementAndGet();
        log.info("[PromptMonitor] 降级到文本格式");
    }
    
    /**
     * 获取监控统计信息
     */
    public Map<String, Object> getStats() {
        long total = totalRequests.get();
        double avgSize = total > 0 ? (double) totalJsonSize.get() / total : 0;
        double failureRate = total > 0 ? (double) parseFailures.get() / total * 100 : 0;
        double fallbackRate = total > 0 ? (double) fallbackToText.get() / total * 100 : 0;
        
        return Map.of(
            "totalRequests", total,
            "totalJsonSizeBytes", totalJsonSize.get(),
            "averageJsonSizeBytes", Math.round(avgSize),
            "parseFailures", parseFailures.get(),
            "fallbackToTextCount", fallbackToText.get(),
            "failureRatePercent", Math.round(failureRate * 100.0) / 100.0,
            "fallbackRatePercent", Math.round(fallbackRate * 100.0) / 100.0,
            "sizeDistribution", Map.copyOf(sizeDistribution)
        );
    }
    
    /**
     * 重置统计数据
     */
    public void resetStats() {
        totalRequests.set(0);
        totalJsonSize.set(0);
        parseFailures.set(0);
        fallbackToText.set(0);
        sizeDistribution.clear();
        log.info("[PromptMonitor] 统计数据已重置");
    }
    
    /**
     * 获取大小分布区间（⭐ 配置化阈值）
     */
    @org.springframework.beans.factory.annotation.Value("${prompt.monitor.size-bucket-1:500}")
    private int sizeBucket1;
    
    @org.springframework.beans.factory.annotation.Value("${prompt.monitor.size-bucket-2:1000}")
    private int sizeBucket2;
    
    @org.springframework.beans.factory.annotation.Value("${prompt.monitor.size-bucket-3:2000}")
    private int sizeBucket3;
    
    @org.springframework.beans.factory.annotation.Value("${prompt.monitor.size-bucket-4:5000}")
    private int sizeBucket4;
    
    private String getSizeBucket(long size) {
        if (size < sizeBucket1) return "<" + sizeBucket1 + "B";
        if (size < sizeBucket2) return sizeBucket1 + "B-" + (sizeBucket2/1000) + "KB";
        if (size < sizeBucket3) return (sizeBucket2/1000) + "KB-" + (sizeBucket3/1000) + "KB";
        if (size < sizeBucket4) return (sizeBucket3/1000) + "KB-" + (sizeBucket4/1000) + "KB";
        return ">" + (sizeBucket4/1000) + "KB";
    }
}
