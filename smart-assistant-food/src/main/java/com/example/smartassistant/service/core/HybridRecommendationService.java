/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.core;

import com.example.smartassistant.service.search.RestaurantReviewSearchService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 餐厅推荐服务 - 基于 RAG 语义搜索
 * 
 * <p>推荐策略：</p>
 * <ul>
 *     <li>RAG 语义搜索 - 理解用户意图</li>
 *     <li>支持多维度过滤（城市、菜系、价格、评分）</li>
 * </ul>
 */
@Slf4j
@Service
public class HybridRecommendationService {
    
    @Autowired
    private RestaurantReviewSearchService ragSearchService;
    
    // ⚠️ 已移除 CollaborativeFilteringService 依赖
    
    // 配置参数
    private static final int FINAL_TOP_N = 5;          // 最终返回数量
    
    /**
     * 推荐餐厅（主入口）- 基于 RAG 语义搜索
     * 
     * @param userId 用户ID（保留参数用于未来扩展）
     * @param query 查询文本
     * @param city 城市
     * @param cuisineType 菜系
     * @param maxPrice 最高价格
     * @param minRating 最低评分
     * @return 推荐结果
     */
    public List<HybridRecommendation> recommendHybrid(
            Long userId,
            String query,
            String city,
            String cuisineType,
            Double maxPrice,
            Double minRating) {
        
        long startTime = System.currentTimeMillis();
        log.info("[Recommendation] 开始推荐: userId={}, query={}", userId, query);
        
        try {
            // 执行 RAG 语义搜索
            List<RestaurantReviewSearchService.RestaurantRecommendation> ragResults = 
                ragSearchService.searchRestaurants(query, city, cuisineType, maxPrice, minRating);
            
            log.info("[Recommendation] RAG 召回 {} 个结果", ragResults.size());
            
            // 转换为 HybridRecommendation 格式
            List<HybridRecommendation> results = ragResults.stream()
                .map(r -> new HybridRecommendation(
                    r.getRestaurantId(),
                    r.getRestaurantName(),
                    r.getCity(),
                    r.getCuisineType(),
                    r.getAddress(),
                    r.getAvgPrice(),
                    r.getRating(),
                    r.getSimilarityScore() != null ? r.getSimilarityScore() : 0,
                    0.0,
                    r.getSimilarityScore() != null ? r.getSimilarityScore() : 0,
                    "rag"
                ))
                .sorted((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()))
                .limit(FINAL_TOP_N)
                .collect(Collectors.toList());
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("[Recommendation] 推荐完成: 生成{}个推荐, 耗时={}ms", 
                    results.size(), duration);
            
            return results;
            
        } catch (Exception e) {
            log.error("[Recommendation] 推荐失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 归一化分数到 0-1 范围（当前未使用，保留用于未来扩展）
     * ⚠️ 已注释，包含对 CollaborativeFilteringService 的引用
     */
    /*
    private Map<String, Double> normalizeScores(List<?> recommendations) {
        if (recommendations.isEmpty()) {
            return Collections.emptyMap();
        }
        
        Map<String, Double> scores = new HashMap<>();
        double maxScore = 0;
        
        // 提取原始分数
        for (Object rec : recommendations) {
            String id = null;
            double score = 0;
            
            if (rec instanceof RestaurantReviewSearchService.RestaurantRecommendation) {
                var r = (RestaurantReviewSearchService.RestaurantRecommendation) rec;
                id = r.getRestaurantId();
                score = r.getSimilarityScore() != null ? r.getSimilarityScore() : 0;
            } else if (rec instanceof CollaborativeFilteringService.CFRecommendation) {
                var c = (CollaborativeFilteringService.CFRecommendation) rec;
                id = c.getRestaurantId();
                score = c.getFinalScore();
            }
            
            if (id != null) {
                scores.put(id, score);
                maxScore = Math.max(maxScore, score);
            }
        }
        
        // 归一化
        if (maxScore > 0) {
            final double finalMaxScore = maxScore;
            scores.replaceAll((k, v) -> v / finalMaxScore);
        }
        
        return scores;
    }
    */
    
    /**
     * 混合推荐结果
     */
    @Data
    public static class HybridRecommendation {
        private final String restaurantId;
        private final String restaurantName;
        private final String city;
        private final String cuisineType;
        private final String address;
        private final Double avgPrice;
        private final Double rating;
        private final double ragScore;
        private final double cfScore;
        private final double finalScore;
        private final String strategy;  // "hybrid", "rag_fallback", "cf_fallback"
        
        public String formatToString() {
            StringBuilder sb = new StringBuilder();
            sb.append("🍽️ ").append(restaurantName).append("\n");
            
            if (city != null) {
                sb.append("📍 ").append(city);
                if (address != null) {
                    sb.append(" | ").append(address);
                }
                sb.append("\n");
            }
            
            if (cuisineType != null) {
                sb.append("🥘 菜系：").append(cuisineType).append("\n");
            }
            
            if (avgPrice != null) {
                sb.append("💰 人均：¥").append(String.format("%.0f", avgPrice)).append("\n");
            }
            
            if (rating != null) {
                sb.append("⭐ 评分：").append(String.format("%.1f", rating)).append("/5.0\n");
            }
            
            sb.append(String.format("🎯 综合推荐度：%.0f%%\n", finalScore * 100));
            sb.append(String.format("   ├─ RAG 得分：%.0f%%\n", ragScore * 100));
            sb.append(String.format("   └─ CF 得分：%.0f%%\n", cfScore * 100));
            sb.append("📊 策略：").append(strategy);
            
            return sb.toString();
        }
    }
}
