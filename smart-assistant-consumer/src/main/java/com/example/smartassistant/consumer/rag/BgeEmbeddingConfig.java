package com.example.smartassistant.consumer.rag;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import com.example.smartassistant.common.memory.EntityProfileService;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class BgeEmbeddingConfig {

    @Bean
    public EntityProfileService entityProfileService(StringRedisTemplate redisTemplate) {
        return new EntityProfileService(redisTemplate);
    }

    @Value("${bge.model.path:models/bge-large-zh-v1.5.onnx}")
    private String modelPath;

    @Value("${bge.vocab.path:models/tokenizer.json}")
    private String vocabPath;

    @Bean
    public BgeEmbeddingModel bgeEmbeddingModel() {
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
