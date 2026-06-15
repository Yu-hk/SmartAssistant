/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.rag;

import com.example.smartassistant.common.document.Document;
import com.example.smartassistant.common.vectorstore.SearchRequest;
import com.example.smartassistant.common.vectorstore.VectorStore;
import com.example.smartassistant.knowledge.TouristAttractionKnowledgeBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 景点向量服务
 * 负责景点数据的向量化存储和语义搜索
 */
@Service
public class AttractionVectorService {

    private static final Logger log = LoggerFactory.getLogger(AttractionVectorService.class);

    private final VectorStore vectorStore;
    private final TouristAttractionKnowledgeBase knowledgeBase;

    public AttractionVectorService(VectorStore vectorStore, 
                                   TouristAttractionKnowledgeBase knowledgeBase) {
        this.vectorStore = vectorStore;
        this.knowledgeBase = knowledgeBase;
    }

    /**
     * 初始化：将所有景点数据导入向量数据库
     */
    @PostConstruct
    public void initialize() {
        log.info("[AttractionVectorService] 开始初始化景点向量数据...");
        
        try {
            // 获取所有景点
            var allCities = knowledgeBase.getAllCities();
            int totalAttractions = 0;

            for (String city : allCities) {
                var attractions = knowledgeBase.getAttractionsByCity(city);
                List<Document> documents = new ArrayList<>();

                for (var attraction : attractions) {
                    // 构建文档内容（用于向量化的文本）
                    String content = buildDocumentContent(attraction);
                    
                    // 构建元数据
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("name", attraction.name());
                    metadata.put("city", attraction.city());
                    metadata.put("province", attraction.province());
                    metadata.put("level", attraction.level());
                    metadata.put("ticketPrice", attraction.ticketPrice());
                    metadata.put("tags", String.join(",", attraction.tags()));
                    metadata.put("type", "attraction");

                    // 创建文档
                    Document document = new Document(
                        UUID.randomUUID().toString(),  // ID
                        content,                        // 文本内容
                        metadata                        // 元数据
                    );
                    
                    documents.add(document);
                    totalAttractions++;
                }

                // 批量添加文档
                if (!documents.isEmpty()) {
                    vectorStore.add(documents);
                    log.info("[AttractionVectorService] 已导入 {} 的 {} 个景点", city, documents.size());
                }
            }

            log.info("[AttractionVectorService] 向量数据初始化完成，共导入 {} 个景点", totalAttractions);
            
        } catch (Exception e) {
            log.error("[AttractionVectorService] 向量数据初始化失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 语义搜索景点
     *
     * @param query 查询文本
     * @param limit 返回数量限制
     * @return 匹配的景点列表
     */
    public List<Map<String, Object>> semanticSearch(String query, int limit) {
        log.info("[AttractionVectorService] 执行语义搜索: query={}, limit={}", query, limit);

        try {
            // 构建搜索请求
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(limit)
                    .similarityThreshold(0.5)  // 相似度阈值
                    .build();

            // 执行搜索
            var results = vectorStore.similaritySearch(searchRequest);

            // 转换结果
            List<Map<String, Object>> formattedResults = new ArrayList<>();
            for (var doc : results) {
                Map<String, Object> result = new HashMap<>();
                result.put("document", doc);
                result.put("metadata", doc.getMetadata());
                result.put("content", doc.getText());
                formattedResults.add(result);
            }

            log.info("[AttractionVectorService] 搜索完成，找到 {} 个结果", formattedResults.size());
            return formattedResults;

        } catch (Exception e) {
            log.error("[AttractionVectorService] 语义搜索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 混合搜索（语义搜索 + 关键词过滤）
     *
     * @param query 查询文本
     * @param filters 过滤条件（如城市、等级等）
     * @param limit 返回数量限制
     * @return 匹配的景点列表
     */
    public List<Map<String, Object>> hybridSearch(String query, Map<String, Object> filters, int limit) {
        log.info("[AttractionVectorService] 执行混合搜索: query={}, filters={}", query, filters);

        try {
            // 先进行语义搜索
            var semanticResults = semanticSearch(query, limit * 2);  // 获取更多候选

            // 应用过滤器
            List<Map<String, Object>> filteredResults = semanticResults.stream()
                    .filter(result -> matchesFilters(result, filters))
                    .limit(limit)
                    .collect(Collectors.toList());

            log.info("[AttractionVectorService] 混合搜索完成，过滤后 {} 个结果", filteredResults.size());
            return filteredResults;

        } catch (Exception e) {
            log.error("[AttractionVectorService] 混合搜索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 构建文档内容（用于向量化的文本）
     */
    private String buildDocumentContent(TouristAttractionKnowledgeBase.AttractionInfo attraction) {
        StringBuilder sb = new StringBuilder();
        sb.append(attraction.name()).append("\n");
        sb.append("位置：").append(attraction.province()).append(" ").append(attraction.city()).append("\n");
        sb.append("等级：").append(attraction.level()).append("级景区\n");
        sb.append("简介：").append(attraction.description()).append("\n");
        
        if (attraction.highlights() != null && !attraction.highlights().isEmpty()) {
            sb.append("亮点：").append(String.join("、", attraction.highlights())).append("\n");
        }
        
        if (attraction.tags() != null && !attraction.tags().isEmpty()) {
            sb.append("标签：").append(String.join("、", attraction.tags())).append("\n");
        }
        
        return sb.toString();
    }

    /**
     * 检查结果是否符合过滤条件
     */
    private boolean matchesFilters(Map<String, Object> result, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) result.get("metadata");
        
        for (Map.Entry<String, Object> filter : filters.entrySet()) {
            String key = filter.getKey();
            Object value = filter.getValue();
            
            if (metadata.containsKey(key)) {
                Object metadataValue = metadata.get(key);
                if (!metadataValue.equals(value)) {
                    return false;
                }
            }
        }
        
        return true;
    }

    /**
     * 删除景点向量数据
     */
    public void deleteAttractionVectors(String attractionName) {
        log.info("[AttractionVectorService] 删除景点向量: {}", attractionName);
        // 实现删除逻辑（需要根据实际 API 调整）
    }

    /**
     * 更新景点向量数据
     */
    public void updateAttractionVector(TouristAttractionKnowledgeBase.AttractionInfo attraction) {
        log.info("[AttractionVectorService] 更新景点向量: {}", attraction.name());
        // 先删除旧的，再添加新的
        deleteAttractionVectors(attraction.name());
        // 重新添加
    }
}
