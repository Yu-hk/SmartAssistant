/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 向量嵌入服务（直接调用 DashScope API，内存优化版）
 * 
 * <p>使用 RestTemplate 替代 WebClient，避免响应式编程的线程池问题</p>
 */
@Service
@Slf4j
public class EmbeddingService {

    private static final String DASHSCOPE_EMBEDDING_URL = 
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate;

    public EmbeddingService() {
        // 创建 RestTemplate 并设置超时
        this.restTemplate = new RestTemplate();
    }

    /**
     * 生成文本向量
     * @param text 输入文本
     * @return float[] 向量数组（1024维 for text-embedding-v3）
     */
    public float[] embed(String text) {
        log.info("[Embedding] embed 被调用: textLength={}", text != null ? text.length() : "null");
        
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("[Embedding] 输入文本为空，跳过向量化");
        }

        try {
            log.info("[Embedding] 开始调用 DashScope API...");
            
            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "text-embedding-v3");
            
            Map<String, Object> input = new HashMap<>();
            input.put("texts", List.of(text));
            requestBody.put("input", input);

            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.info("[Embedding] 发送请求到 DashScope...");
            
            // 发送请求
            ResponseEntity<Map> response = restTemplate.exchange(
                    DASHSCOPE_EMBEDDING_URL,
                    HttpMethod.POST,
                    entity,
                    Map.class
            );
            
            log.info("[Embedding] 收到响应: status={}", response.getStatusCode());

            // 解析响应：text-embedding-v3 格式为 output.embeddings[0].embedding
            Map<String, Object> responseBody = response.getBody();
            if (responseBody != null && responseBody.containsKey("output")) {
                Map<String, Object> output = (Map<String, Object>) responseBody.get("output");
                if (output != null && output.containsKey("embeddings")) {
                    List<Map<String, Object>> embeddings = (List<Map<String, Object>>) output.get("embeddings");
                    if (embeddings != null && !embeddings.isEmpty()) {
                        Map<String, Object> firstEmbedding = embeddings.get(0);
                        Object embeddingObj = firstEmbedding.get("embedding");
                        if (embeddingObj instanceof List) {
                            List<Number> embeddingList = (List<Number>) embeddingObj;
                            float[] result = new float[embeddingList.size()];
                            for (int i = 0; i < embeddingList.size(); i++) {
                                result[i] = embeddingList.get(i).floatValue();
                            }
                            log.info("[Embedding] 向量生成成功: dimensions={}", result.length);
                            return result;
                        }
                    }
                }
            }

            log.warn("[Embedding] 响应格式异常，抛出异常");
            throw new RuntimeException("DashScope 响应格式异常，缺少 embedding 数据");
            
        } catch (Exception e) {
            log.error("[Embedding] 生成向量失败: {}", e.getMessage(), e);
            throw new RuntimeException("DashScope 向量生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量生成向量（逐个处理避免内存问题）
     * @param texts 输入文本列表
     * @return List<float[]> 向量数组列表
     */
    public List<float[]> embed(List<String> texts) {
        List<float[]> results = new ArrayList<>(texts.size());
        for (String text : texts) {
            results.add(embed(text));
        }
        return results;
    }

    /**
     * 获取向量维度
     */
    public int dimensions() {
        return 1024;
    }
}
