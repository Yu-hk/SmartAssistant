/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 餐厅评论向量化导入工具
 * 
 * <p>功能：</p>
 * <ul>
 *     <li>应用启动时自动为没有向量的评论生成 embedding</li>
 *     <li>批量处理，避免一次性加载过多数据</li>
 *     <li>支持断点续传（只处理 embedding 为空的记录）</li>
 * </ul>
 * 
 * <p>使用方式：</p>
 * <ul>
 *     <li>首次部署：执行 SQL 导入数据后，重启应用自动向量化</li>
 *     <li>增量更新：新增评论后，重启应用或手动调用接口</li>
 * </ul>
 */
@Slf4j
@Component
public class ReviewEmbeddingInitializer implements CommandLineRunner {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private EmbeddingModel embeddingModel;
    
    private static final int BATCH_SIZE = 50;  // 每批处理50条
    
    @Override
    public void run(String... args) throws Exception {
        log.info("[ReviewEmbeddingInitializer] 开始检查需要向量化的评论数据...");
        
        try {
            int totalProcessed = processMissingEmbeddings();
            
            if (totalProcessed > 0) {
                log.info("[ReviewEmbeddingInitializer] ✅ 完成！共处理 {} 条评论", totalProcessed);
            } else {
                log.info("[ReviewEmbeddingInitializer] ℹ️ 所有评论已有向量，无需处理");
            }
            
        } catch (Exception e) {
            log.error("[ReviewEmbeddingInitializer] ❌ 向量化失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 处理缺少向量的评论
     */
    private int processMissingEmbeddings() {
        int totalProcessed = 0;
        int offset = 0;
        
        while (true) {
            // 查询未向量化的评论
            String sql = "SELECT id, review_text FROM restaurant_reviews_vector " +
                        "WHERE embedding IS NULL LIMIT ? OFFSET ?";
            
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, BATCH_SIZE, offset);
            
            if (rows.isEmpty()) {
                break;  // 没有更多数据
            }
            
            log.info("[ReviewEmbeddingInitializer] 处理第 {}-{} 条...", offset + 1, offset + rows.size());
            
            // 批量生成向量
            for (Map<String, Object> row : rows) {
                Long id = ((Number) row.get("id")).longValue();
                String reviewText = (String) row.get("review_text");
                
                try {
                    // 生成向量
                    float[] embedding = generateEmbedding(reviewText);
                    
                    // 更新数据库
                    updateEmbedding(id, embedding);
                    
                    totalProcessed++;
                    
                } catch (Exception e) {
                    log.error("[ReviewEmbeddingInitializer] 处理评论 ID={} 失败: {}", id, e.getMessage());
                    // 继续处理下一条
                }
            }
            
            offset += BATCH_SIZE;
            
            // 避免频繁请求 AI API
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[ReviewEmbeddingInitializer] 向量化过程被中断");
                break;
            }
        }
        
        return totalProcessed;
    }
    
    /**
     * 生成文本向量
     */
    private float[] generateEmbedding(String text) {
        try {
            List<float[]> embeddings = embeddingModel.embed(List.of(text));
            return embeddings.get(0);
        } catch (Exception e) {
            throw new RuntimeException("向量生成失败", e);
        }
    }
    
    /**
     * 更新数据库中的向量
     */
    private void updateEmbedding(Long id, float[] embedding) {
        // 将 float[] 转换为 PostgreSQL vector 格式
        StringBuilder vectorStr = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) vectorStr.append(",");
            vectorStr.append(embedding[i]);
        }
        vectorStr.append("]");
        
        String sql = "UPDATE restaurant_reviews_vector SET embedding = ?::vector WHERE id = ?";
        jdbcTemplate.update(sql, vectorStr.toString(), id);
        
        log.debug("[ReviewEmbeddingInitializer] 已更新评论 ID={} 的向量", id);
    }
}
