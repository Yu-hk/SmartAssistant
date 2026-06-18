package com.example.smartassistant.router.service.cache;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * BGE ONNX 嵌入服务 — 使用 BGE ONNX 模型。
 * <p>
 * <b>重构</b>：委托给 common 模块的 {@link BgeEmbeddingModel}，消除重复实现。
 * 默认加载 classpath:models/bge-small-zh-v1.5.onnx，
 * 模型不存在时 isAvailable() 返回 false，系统降级使用 TF 向量。
 * </p>
 */
@Service
public class BgeOnnxEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(BgeOnnxEmbeddingService.class);

    private BgeEmbeddingModel delegate;

    @PostConstruct
    public void init() {
        try {
            // 优先从外部文件加载，回退到 classpath
            String modelPath = "../models/bge-large-zh-v1.5.onnx";
            java.io.File externalModel = new java.io.File(modelPath);
            if (!externalModel.exists()) {
                modelPath = "models/bge-small-zh-v1.5.onnx";
            }
            String vocabPath = "../models/tokenizer.json";
            java.io.File externalVocab = new java.io.File(vocabPath);
            if (!externalVocab.exists()) {
                vocabPath = "models/tokenizer.json";
            }
            delegate = new BgeEmbeddingModel(modelPath, vocabPath);
            log.info("[BGE] 委托到 common.BgeEmbeddingModel, dim={}, available={}",
                    delegate.dimensions(), delegate.isAvailable());
        } catch (Exception e) {
            log.warn("[BGE] 初始化失败: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (delegate != null) {
            try { delegate.close(); } catch (Exception ignored) {}
        }
    }

    public boolean isAvailable() {
        return delegate != null && delegate.isAvailable();
    }

    /** 将文本转为 embedDim 维归一化向量 */
    public float[] embed(String text) {
        if (delegate == null || !delegate.isAvailable()) return null;
        return delegate.embedding(text);
    }
}
