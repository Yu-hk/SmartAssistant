package com.example.smartassistant.embedding;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 向量嵌入 REST API
 */
@RestController
@RequestMapping("/api/embedding")
public class EmbeddingController {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingController.class);

    private final BgeEmbeddingModel embeddingModel;

    public EmbeddingController(BgeEmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", embeddingModel.isAvailable() ? "UP" : "DOWN");
        result.put("model", "bge-large-zh-v1.5");
        result.put("dimensions", embeddingModel.dimensions());
        result.put("available", embeddingModel.isAvailable());
        return result;
    }

    /**
     * 获取向量维度
     */
    @GetMapping("/dimensions")
    public Map<String, Object> dimensions() {
        return Map.of("dimensions", embeddingModel.dimensions());
    }

    /**
     * 单条文本嵌入
     * POST /api/embedding
     * Body: {"text": "成都有什么美食？"}
     */
    @PostMapping
    public Map<String, Object> embed(@RequestBody Map<String, String> request) {
        String text = request.get("text");
        if (text == null || text.isBlank()) {
            return Map.of("error", "text 不能为空");
        }

        long start = System.currentTimeMillis();
        float[] vec = embeddingModel.embedding(text);
        long elapsed = System.currentTimeMillis() - start;

        if (vec == null) {
            return Map.of("error", "嵌入失败，模型不可用");
        }

        // float[] → List<Float> 用于 JSON 序列化
        List<Float> embedding = new ArrayList<>(vec.length);
        for (float v : vec) {
            embedding.add(v);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("embedding", embedding);
        result.put("dimensions", vec.length);
        result.put("duration_ms", elapsed);

        log.debug("[Embedding] text='{}' dim={} duration={}ms",
                text.length() > 20 ? text.substring(0, 20) + "..." : text,
                vec.length, elapsed);

        return result;
    }

    /**
     * 批量文本嵌入
     * POST /api/embedding/batch
     * Body: {"texts": ["成都有什么美食？", "北京天气怎么样？"]}
     */
    @PostMapping("/batch")
    public Map<String, Object> embedBatch(@RequestBody Map<String, List<String>> request) {
        List<String> texts = request.get("texts");
        if (texts == null || texts.isEmpty()) {
            return Map.of("error", "texts 不能为空");
        }

        long start = System.currentTimeMillis();
        List<List<Float>> embeddings = new ArrayList<>(texts.size());
        for (String text : texts) {
            float[] vec = embeddingModel.embedding(text);
            if (vec != null) {
                List<Float> emb = new ArrayList<>(vec.length);
                for (float v : vec) emb.add(v);
                embeddings.add(emb);
            } else {
                embeddings.add(Collections.emptyList());
            }
        }
        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("embeddings", embeddings);
        result.put("dimensions", embeddingModel.dimensions());
        result.put("count", texts.size());
        result.put("duration_ms", elapsed);

        log.info("[Embedding] 批量嵌入 {} 条, dim={}, duration={}ms",
                texts.size(), embeddingModel.dimensions(), elapsed);

        return result;
    }
}
