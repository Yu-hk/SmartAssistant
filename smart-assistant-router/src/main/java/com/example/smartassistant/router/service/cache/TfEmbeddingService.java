package com.example.smartassistant.router.service.cache;

import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地 TF 向量嵌入服务 — 基于 ChineseTokenizer 的词频向量。
 * <p>
 * 将问题分词后构建 TF (Term Frequency) 向量，用于余弦相似度匹配。
 * 零外部依赖，零 API 调用，完全本地运行。
 * 词典自适应增长，支持中英文混合文本。
 */
@Service
public class TfEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(TfEmbeddingService.class);

    private final ChineseTokenizer tokenizer;
    private final Map<String, Integer> vocab = new ConcurrentHashMap<>();
    private final AtomicInteger vocabSize = new AtomicInteger(0);
    private boolean initialized = false;

    public TfEmbeddingService(ChineseTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    public boolean isAvailable() {
        return true;
    }

    /**
     * 将文本转为 TF 向量
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            Set<String> terms = tokenizer.tokenize(text);
            if (terms.isEmpty()) return null;

            // Build term frequency for this text
            Map<String, Integer> tf = new HashMap<>();
            for (String term : terms) {
                int idx = vocab.computeIfAbsent(term, k -> {
                    int id = vocabSize.getAndIncrement();
                    initialized = true;
                    return id;
                });
                tf.merge(term, 1, Integer::sum);
            }

            int dim = vocabSize.get();
            if (dim == 0) return null;

            float[] vector = new float[dim];
            for (var entry : tf.entrySet()) {
                Integer idx = vocab.get(entry.getKey());
                if (idx != null && idx < dim) {
                    vector[idx] = (float) entry.getValue();
                }
            }

            // L2 normalize
            double norm = 0;
            for (float v : vector) norm += (double) v * v;
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < dim; i++) {
                    vector[i] /= (float) norm;
                }
            }

            return vector;
        } catch (Exception e) {
            log.warn("[TFEmbedding] 向量化失败: {}", e.getMessage());
            return null;
        }
    }

    public int getVocabSize() {
        return vocabSize.get();
    }
}
