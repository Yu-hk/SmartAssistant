package com.example.smartassistant.common.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 远程嵌入客户端 — 通过 HTTP 调用 embedding-service 获取向量
 * 
 * <p>实现 {@link EmbeddingModel} 接口，可作为 Spring AI 的嵌入模型注入，
 * 替代本地加载的 BgeEmbeddingModel。Consumer/Order/Product 通过此客户端
 * 共享 embedding-service 中的 BGE 模型，避免各自加载 1.3GB 文件。</p>
 * 
 * <p>启用条件：{@code embedding.service.url} 属性已设置</p>
 */
@Component
@ConditionalOnProperty(name = "embedding.service.url")
public class EmbeddingClient implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final RestClient restClient;
    private final String serviceUrl;

    private volatile Integer cachedDimensions;

    public EmbeddingClient(@Value("${embedding.service.url}") String serviceUrl) {
        this.serviceUrl = serviceUrl;
        this.restClient = RestClient.builder()
                .baseUrl(serviceUrl)
                .build();
        log.info("[EmbeddingClient] 初始化，远程服务地址: {}", serviceUrl);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        if (instructions.size() == 1) {
            // 单条 — 调用 POST /api/embedding
            Map<String, Object> resp = restClient.post()
                    .uri("/api/embedding")
                    .body(Map.of("text", instructions.get(0)))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            if (resp == null || resp.containsKey("error")) {
                log.warn("[EmbeddingClient] 嵌入失败: {}", resp);
                return new EmbeddingResponse(List.of());
            }

            @SuppressWarnings("unchecked")
            List<Number> embList = (List<Number>) resp.get("embedding");
            float[] vec = new float[embList.size()];
            for (int i = 0; i < embList.size(); i++) {
                vec[i] = embList.get(i).floatValue();
            }
            return new EmbeddingResponse(List.of(new Embedding(vec, 0)));
        }

        // 多条 — 调用 POST /api/embedding/batch
        Map<String, Object> resp = restClient.post()
                .uri("/api/embedding/batch")
                .body(Map.of("texts", instructions))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        if (resp == null || resp.containsKey("error")) {
            log.warn("[EmbeddingClient] 批量嵌入失败: {}", resp);
            return new EmbeddingResponse(List.of());
        }

        @SuppressWarnings("unchecked")
        List<List<Number>> embLists = (List<List<Number>>) resp.get("embeddings");
        List<Embedding> results = new ArrayList<>(embLists.size());
        for (int idx = 0; idx < embLists.size(); idx++) {
            List<Number> embList = embLists.get(idx);
            if (embList == null || embList.isEmpty()) continue;
            float[] vec = new float[embList.size()];
            for (int i = 0; i < embList.size(); i++) {
                vec[i] = embList.get(i).floatValue();
            }
            results.add(new Embedding(vec, idx));
        }
        return new EmbeddingResponse(results);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        Map<String, Object> resp = restClient.post()
                .uri("/api/embedding")
                .body(Map.of("text", text))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        if (resp == null || resp.containsKey("error")) {
            log.warn("[EmbeddingClient] embed 失败: {}", resp);
            return null;
        }

        @SuppressWarnings("unchecked")
        List<Number> embList = (List<Number>) resp.get("embedding");
        float[] vec = new float[embList.size()];
        for (int i = 0; i < embList.size(); i++) {
            vec[i] = embList.get(i).floatValue();
        }
        return vec;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        Map<String, Object> resp = restClient.post()
                .uri("/api/embedding/batch")
                .body(Map.of("texts", texts))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        if (resp == null || resp.containsKey("error")) {
            log.warn("[EmbeddingClient] 批量 embed 失败: {}", resp);
            return List.of();
        }

        @SuppressWarnings("unchecked")
        List<List<Number>> embLists = (List<List<Number>>) resp.get("embeddings");
        return embLists.stream()
                .map(embList -> {
                    if (embList == null || embList.isEmpty()) return null;
                    float[] vec = new float[embList.size()];
                    for (int i = 0; i < embList.size(); i++) {
                        vec[i] = embList.get(i).floatValue();
                    }
                    return vec;
                })
                .collect(Collectors.toList());
    }

    @Override
    public int dimensions() {
        if (cachedDimensions != null) {
            return cachedDimensions;
        }
        try {
            Map<String, Object> resp = restClient.get()
                    .uri("/api/embedding/dimensions")
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            if (resp != null && resp.containsKey("dimensions")) {
                cachedDimensions = (Integer) resp.get("dimensions");
                return cachedDimensions;
            }
        } catch (Exception e) {
            log.warn("[EmbeddingClient] 获取维度失败: {}", e.getMessage());
        }
        return 1024; // 降级默认值
    }
}
