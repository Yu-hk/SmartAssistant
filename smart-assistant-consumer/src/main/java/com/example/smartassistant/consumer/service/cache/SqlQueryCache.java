package com.example.smartassistant.consumer.service.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * SQL 查询结果缓存服务
 * 用于缓存频繁执行的统计查询结果
 */
@Service
@Slf4j
public class SqlQueryCache {
    
    private final RedisTemplate<String, Object> redisTemplate;
    
    // 默认缓存时间：5分钟
    private static final long DEFAULT_TTL = 300;
    
    public SqlQueryCache(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    /**
     * 获取缓存的查询结果
     * @param cacheKey 缓存键（通常是 SQL 语句的哈希）
     * @return 缓存的结果，如果不存在返回 null
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> get(String cacheKey) {
        try {
            String key = "sql:cache:" + cacheKey;
            Object cached = redisTemplate.opsForValue().get(key);
            
            if (cached != null) {
                log.debug("[SQL Cache] ✅ 缓存命中: {}", cacheKey);
                return (List<Map<String, Object>>) cached;
            }
            
            log.debug("[SQL Cache] ❌ 缓存未命中: {}", cacheKey);
            return null;
            
        } catch (Exception e) {
            log.error("[SQL Cache] 读取缓存失败: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 缓存查询结果
     * @param cacheKey 缓存键
     * @param result 查询结果
     * @param ttl 过期时间（秒）
     */
    public void put(String cacheKey, List<Map<String, Object>> result, long ttl) {
        try {
            String key = "sql:cache:" + cacheKey;
            redisTemplate.opsForValue().set(key, result, ttl, TimeUnit.SECONDS);
            log.debug("[SQL Cache] ✅ 缓存已设置: {} (TTL: {}s)", cacheKey, ttl);
            
        } catch (Exception e) {
            log.error("[SQL Cache] 写入缓存失败: {}", e.getMessage());
        }
    }
    
    /**
     * 缓存查询结果（使用默认 TTL）
     */
    public void put(String cacheKey, List<Map<String, Object>> result) {
        put(cacheKey, result, DEFAULT_TTL);
    }
    
    /**
     * 删除缓存
     * @param cacheKey 缓存键
     */
    public void evict(String cacheKey) {
        try {
            String key = "sql:cache:" + cacheKey;
            Boolean deleted = redisTemplate.delete(key);
            if (deleted) {
                log.debug("[SQL Cache] 🗑️ 缓存已删除: {}", cacheKey);
            }
        } catch (Exception e) {
            log.error("[SQL Cache] 删除缓存失败: {}", e.getMessage());
        }
    }
    
    /**
     * 清除所有 SQL 缓存
     */
    public void clearAll() {
        try {
            // 使用 pattern 删除所有 sql:cache:* 键
            redisTemplate.keys("sql:cache:*").forEach(redisTemplate::delete);
            log.info("[SQL Cache] 🗑️ 所有 SQL 缓存已清除");
        } catch (Exception e) {
            log.error("[SQL Cache] 清除所有缓存失败: {}", e.getMessage());
        }
    }
    
    /**
     * 生成缓存键（基于 SQL 语句）
     * @param sql SQL 语句
     * @return 缓存键（MD5 哈希）
     */
    public String generateCacheKey(String sql) {
        // 简单实现：直接使用 SQL 的 hashCode
        // 生产环境建议使用 MD5 或 SHA256
        return Integer.toHexString(sql.hashCode());
    }
    
    /**
     * 判断是否为可缓存的查询
     * 只缓存统计类查询，不缓存列表查询
     * 
     * @param sql SQL 语句
     * @return 是否可缓存
     */
    public boolean isCacheable(String sql) {
        String upperSql = sql.toUpperCase().trim();
        
        // 只缓存包含聚合函数的查询
        boolean hasAggregate = upperSql.contains("COUNT(") ||
                              upperSql.contains("SUM(") ||
                              upperSql.contains("AVG(") ||
                              upperSql.contains("MAX(") ||
                              upperSql.contains("MIN(");
        
        // 不缓存包含 LIMIT 的查询（通常是列表查询）
        boolean hasLimit = upperSql.contains("LIMIT");
        
        return hasAggregate && !hasLimit;
    }
}

