package com.example.smartassistant.consumer.rag;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;

/**
 * BGE 嵌入配置 — 条件自动切换
 * 
 * <p>当 {@code embedding.service.url} 已设置时，{@link com.example.smartassistant.common.embedding.EmbeddingClient}
 * 自动生效作为 {@link EmbeddingModel}，本配置跳过本地 BGE 加载。</p>
 * 
 * <p>当 {@code embedding.service.url} 未设置时，本配置创建本地 BgeEmbeddingModel（向后兼容）。</p>
 */
@Configuration
@ConditionalOnMissingBean(EmbeddingModel.class)
public class BgeEmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(BgeEmbeddingConfig.class);

    @Value("${bge.model.path:models/bge-large-zh-v1.5.onnx}")
    private String modelPath;

    @Value("${bge.vocab.path:models/tokenizer.json}")
    private String vocabPath;

    @Bean
    public BgeEmbeddingModel bgeEmbeddingModel() {
        log.info("[BGE] 本地模式：加载 BGE 模型 (path={})", modelPath);
        return new BgeEmbeddingModel(modelPath, vocabPath);
    }

    @Bean
    @Primary
    public EmbeddingModel embeddingModel(BgeEmbeddingModel bge) {
        return new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                var results = new ArrayList<org.springframework.ai.embedding.Embedding>();
                for (int i = 0; i < request.getInstructions().size(); i++) {
                    float[] vec = bge.embedding(request.getInstructions().get(i));
                    if (vec != null) {
                        results.add(new org.springframework.ai.embedding.Embedding(vec, i));
                    }
                }
                return new EmbeddingResponse(results);
            }

            @Override
            public float[] embed(Document document) {
                return bge.embedding(document.getText());
            }

            @Override
            public float[] embed(String text) {
                return bge.embedding(text);
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                var results = new ArrayList<float[]>();
                for (String t : texts) results.add(bge.embedding(t));
                return results;
            }

            @Override
            public int dimensions() {
                return bge.dimensions();
            }
        };
    }
}
