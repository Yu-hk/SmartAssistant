package com.example.smartassistant.embedding;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import jakarta.annotation.PreDestroy;

/**
 * 独立 BGE 向量嵌入服务
 * 
 * <p>职责：加载 BGE 模型一次，通过 REST API 提供嵌入服务。
 * Consumer/Order/Product 通过 EmbeddingClient 调用本服务，
 * 避免每个服务各自加载 1.3GB 模型。</p>
 */
@SpringBootApplication
@EnableDiscoveryClient
@ComponentScan(basePackages = "com.example.smartassistant.embedding")
public class EmbeddingApplication {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingApplication.class);

    private BgeEmbeddingModel bgeModel;

    public static void main(String[] args) {
        SpringApplication.run(EmbeddingApplication.class, args);
    }

    @Value("${bge.model.path:models/bge-large-zh-v1.5.onnx}")
    private String modelPath;

    @Value("${bge.vocab.path:models/tokenizer.json}")
    private String vocabPath;

    @Bean
    public BgeEmbeddingModel bgeEmbeddingModel() {
        log.info("[EmbeddingService] 正在加载 BGE 模型...");
        long start = System.currentTimeMillis();
        bgeModel = new BgeEmbeddingModel(modelPath, vocabPath);
        long elapsed = System.currentTimeMillis() - start;
        if (bgeModel.isAvailable()) {
            log.info("[EmbeddingService] BGE 模型加载完成，维度={}，耗时={}ms",
                    bgeModel.dimensions(), elapsed);
        } else {
            log.warn("[EmbeddingService] BGE 模型加载失败，服务将以降级模式运行");
        }
        return bgeModel;
    }

    @PreDestroy
    public void cleanup() {
        if (bgeModel != null) {
            bgeModel.close();
            log.info("[EmbeddingService] BGE 模型已释放");
        }
    }
}
