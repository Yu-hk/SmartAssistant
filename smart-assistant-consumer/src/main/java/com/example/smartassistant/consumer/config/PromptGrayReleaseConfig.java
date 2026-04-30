package com.example.smartassistant.consumer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Prompt 灰度发布配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "prompt.gray-release")
public class PromptGrayReleaseConfig {
    
    /**
     * 是否启用灰度发布
     */
    private boolean enabled = false;
    
    /**
     * 灰度比例（0-100）
     * 0 = 全部使用旧版本（文本格式）
     * 100 = 全部使用新版本（JSON 格式）
     */
    private int percentage = 0;
    
    /**
     * 白名单用户 ID 列表（始终使用新版本）
     */
    private java.util.Set<Long> whitelistUsers = new java.util.HashSet<>();
    
    /**
     * 黑名单用户 ID 列表（始终使用旧版本）
     */
    private java.util.Set<Long> blacklistUsers = new java.util.HashSet<>();
    
    /**
     * 判断指定用户是否应该使用新版本（JSON 格式）
     */
    public boolean shouldUseJsonFormat(Long userId) {
        // 如果未启用灰度，全部使用旧版本
        if (!enabled) {
            return false;
        }
        
        // 白名单用户始终使用新版本
        if (whitelistUsers.contains(userId)) {
            return true;
        }
        
        // 黑名单用户始终使用旧版本
        if (blacklistUsers.contains(userId)) {
            return false;
        }
        
        // 根据灰度比例随机决定
        if (percentage <= 0) {
            return false;
        }
        if (percentage >= 100) {
            return true;
        }
        
        // 基于 userId 的哈希值决定（保证同一用户始终走相同分支）
        int hash = userId != null ? Math.abs(userId.hashCode()) : 0;
        return (hash % 100) < percentage;
    }
    
    /**
     * 获取当前灰度状态描述
     */
    public String getStatusDescription() {
        if (!enabled) {
            return "灰度发布未启用，全部使用旧版本";
        }
        return String.format("灰度发布已启用，比例=%d%%, 白名单=%d人, 黑名单=%d人",
            percentage, whitelistUsers.size(), blacklistUsers.size());
    }
}
