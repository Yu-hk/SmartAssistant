package com.example.smartassistant.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 餐厅评论语义搜索服务
 * 
 * <p>功能：</p>
 * <ul>
 *     <li>基于用户查询语义搜索相似餐厅评论</li>
 *     <li>支持多维度过滤（城市、菜系、价格、评分）</li>
 *     <li>返回 Top-N 相关餐厅推荐</li>
 * </ul>
 * 
 * <p>使用场景：</p>
 * <ul>
 *     <li>"我想找一家环境好的西餐厅，人均200左右"</li>
 *     <li>"适合家庭聚会的川菜馆"</li>
 *     <li>"有儿童座椅的火锅店"</li>
 * </ul>
 */
@Slf4j
@Service
public class RestaurantReviewSearchService {
    
    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    
    // 配置参数
    @Value("${food.semantic-search.top-k:5}")
    private int topK;
    
    @Value("${food.semantic-search.similarity-threshold:0.7}")
    private double similarityThreshold;
    
    public RestaurantReviewSearchService(VectorStore vectorStore,
                                         EmbeddingModel embeddingModel,
                                         JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.jdbcTemplate = jdbcTemplate;
        
        log.info("[RestaurantReviewSearch] 初始化完成 - topK={}, threshold={}", 
                topK, similarityThreshold);
    }
    
    /**
     * 语义搜索餐厅（主入口）
     * 
     * @param query 用户查询（自然语言）
     * @param city 城市过滤（可选）
     * @param cuisineType 菜系过滤（可选）
     * @param maxPrice 最高人均价格（可选）
     * @param minRating 最低评分（可选）
     * @return 餐厅推荐列表
     */
    public List<RestaurantRecommendation> searchRestaurants(String query,
                                                             String city,
                                                             String cuisineType,
                                                             Double maxPrice,
                                                             Double minRating) {
        long startTime = System.currentTimeMillis();
        log.info("[RestaurantReviewSearch] 开始搜索: query={}, city={}, cuisine={}", 
                query, city, cuisineType);
        
        try {
            // 1. 生成查询向量
            float[] queryEmbedding = generateEmbedding(query);
            
            // 2. 构建搜索请求
            SearchRequest searchRequest = buildSearchRequest(queryEmbedding, city, cuisineType);
            
            // 3. 执行向量搜索
            List<Document> results = vectorStore.similaritySearch(searchRequest);
            
            if (results.isEmpty()) {
                log.warn("[RestaurantReviewSearch] 未找到匹配的餐厅");
                return Collections.emptyList();
            }
            
            // 4. 后处理：过滤和排序
            List<RestaurantRecommendation> recommendations = postProcessResults(
                results, maxPrice, minRating);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("[RestaurantReviewSearch] 搜索完成: 找到{}个结果, 耗时={}ms", 
                    recommendations.size(), duration);
            
            return recommendations;
            
        } catch (Exception e) {
            log.error("[RestaurantReviewSearch] 搜索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 生成文本向量
     */
    private float[] generateEmbedding(String text) {
        try {
            List<float[]> embeddings = embeddingModel.embed(List.of(text));
            return embeddings.get(0);
        } catch (Exception e) {
            log.error("[RestaurantReviewSearch] 生成向量失败: {}", e.getMessage());
            throw new RuntimeException("向量生成失败", e);
        }
    }
    
    /**
     * 构建搜索请求
     */
    private SearchRequest buildSearchRequest(float[] queryEmbedding, 
                                              String city, 
                                              String cuisineType) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(new String())  // 占位符，实际使用向量
                .topK(topK * 2)  // 先多召回一些，后续再过滤
                .similarityThreshold(similarityThreshold);
        
        // 构建过滤表达式
        List<String> filters = new ArrayList<>();
        if (city != null && !city.isEmpty()) {
            filters.add("city == '" + city + "'");
        }
        if (cuisineType != null && !cuisineType.isEmpty()) {
            filters.add("cuisine_type == '" + cuisineType + "'");
        }
        
        if (!filters.isEmpty()) {
            String filterExpression = String.join(" && ", filters);
            builder.filterExpression(filterExpression);
            log.debug("[RestaurantReviewSearch] 过滤条件: {}", filterExpression);
        }
        
        return builder.build();
    }
    
    /**
     * 后处理结果：过滤和排序
     */
    private List<RestaurantRecommendation> postProcessResults(List<Document> documents,
                                                                Double maxPrice,
                                                                Double minRating) {
        List<RestaurantRecommendation> recommendations = new ArrayList<>();
        
        for (Document doc : documents) {
            Map<String, Object> metadata = doc.getMetadata();
            
            // 提取字段
            String restaurantId = (String) metadata.get("restaurant_id");
            String restaurantName = (String) metadata.get("restaurant_name");
            String city = (String) metadata.get("city");
            String cuisineType = (String) metadata.get("cuisine_type");
            String address = (String) metadata.get("address");
            Double avgPrice = metadata.get("avg_price") instanceof Number 
                ? ((Number) metadata.get("avg_price")).doubleValue() 
                : null;
            Double rating = metadata.get("rating") instanceof Number 
                ? ((Number) metadata.get("rating")).doubleValue() 
                : null;
            String reviewText = (String) metadata.get("review_text");
            @SuppressWarnings("unchecked")
            List<String> tags = metadata.get("review_tags") instanceof List
                ? (List<String>) metadata.get("review_tags")
                : Collections.emptyList();
            
            // 价格过滤
            if (maxPrice != null && avgPrice != null && avgPrice > maxPrice) {
                continue;
            }
            
            // 评分过滤
            if (minRating != null && rating != null && rating < minRating) {
                continue;
            }
            
            // 计算相关性得分
            Double similarity = doc.getScore();
            
            // 创建推荐对象
            RestaurantRecommendation recommendation = RestaurantRecommendation.builder()
                    .restaurantId(restaurantId)
                    .restaurantName(restaurantName)
                    .city(city)
                    .cuisineType(cuisineType)
                    .address(address)
                    .avgPrice(avgPrice)
                    .rating(rating)
                    .reviewSummary(generateReviewSummary(reviewText))
                    .tags(tags)
                    .similarityScore(similarity)
                    .build();
            
            recommendations.add(recommendation);
        }
        
        // 按相似度排序
        recommendations.sort((a, b) -> Double.compare(b.getSimilarityScore(), a.getSimilarityScore()));
        
        // 限制返回数量
        return recommendations.stream()
                .limit(topK)
                .collect(Collectors.toList());
    }
    
    /**
     * 生成评论摘要（取前100字）
     */
    private String generateReviewSummary(String reviewText) {
        if (reviewText == null || reviewText.isEmpty()) {
            return "暂无评论";
        }
        return reviewText.length() > 100 
            ? reviewText.substring(0, 100) + "..." 
            : reviewText;
    }
    
    /**
     * 获取热门餐厅（按评分和评论数排序）
     */
    public List<RestaurantRecommendation> getPopularRestaurants(String city, 
                                                                  String cuisineType,
                                                                  int limit) {
        StringBuilder sql = new StringBuilder(
            "SELECT restaurant_id, restaurant_name, city, cuisine_type, address, " +
            "avg_price, rating, review_text, review_tags " +
            "FROM restaurant_reviews_vector WHERE 1=1"
        );
        
        List<Object> params = new ArrayList<>();
        
        if (city != null && !city.isEmpty()) {
            sql.append(" AND city = ?");
            params.add(city);
        }
        
        if (cuisineType != null && !cuisineType.isEmpty()) {
            sql.append(" AND cuisine_type = ?");
            params.add(cuisineType);
        }
        
        sql.append(" ORDER BY rating DESC LIMIT ?");
        params.add(limit);
        
        try {
            return jdbcTemplate.query(sql.toString(), params.toArray(), (rs, rowNum) -> {
                return RestaurantRecommendation.builder()
                        .restaurantId(rs.getString("restaurant_id"))
                        .restaurantName(rs.getString("restaurant_name"))
                        .city(rs.getString("city"))
                        .cuisineType(rs.getString("cuisine_type"))
                        .address(rs.getString("address"))
                        .avgPrice(rs.getDouble("avg_price"))
                        .rating(rs.getDouble("rating"))
                        .reviewSummary(generateReviewSummary(rs.getString("review_text")))
                        .build();
            });
        } catch (Exception e) {
            log.error("[RestaurantReviewSearch] 查询热门餐厅失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 餐厅推荐结果
     */
    @Data
    @lombok.Builder
    public static class RestaurantRecommendation {
        private String restaurantId;
        private String restaurantName;
        private String city;
        private String cuisineType;
        private String address;
        private Double avgPrice;
        private Double rating;
        private String reviewSummary;
        private List<String> tags;
        private Double similarityScore;  // 语义相似度得分
        
        /**
         * 格式化为可读字符串
         */
        public String formatToString() {
            StringBuilder sb = new StringBuilder();
            sb.append("🍽️ ").append(restaurantName).append("\n");
            sb.append("📍 ").append(address).append("\n");
            
            if (cuisineType != null) {
                sb.append("🥘 菜系：").append(cuisineType).append("\n");
            }
            
            if (avgPrice != null) {
                sb.append("💰 人均：¥").append(String.format("%.0f", avgPrice)).append("\n");
            }
            
            if (rating != null) {
                sb.append("⭐ 评分：").append(String.format("%.1f", rating)).append("/5.0\n");
            }
            
            if (tags != null && !tags.isEmpty()) {
                sb.append("🏷️ 标签：").append(String.join("、", tags)).append("\n");
            }
            
            if (reviewSummary != null) {
                sb.append("💬 评价：").append(reviewSummary).append("\n");
            }
            
            if (similarityScore != null) {
                sb.append("🎯 匹配度：").append(String.format("%.0f%%", similarityScore * 100)).append("\n");
            }
            
            return sb.toString();
        }
    }
}
