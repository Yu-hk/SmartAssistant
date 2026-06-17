/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 向量嵌入服务
 * 
 * <p>使用嵌入模型（本地 BGE 或远程 embedding-service）生成文本向量。
 * 通过 {@link EmbeddingModel} 接口注入，支持本地/远程透明切换。</p>
 */
@Service
@Slf4j
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        log.info("[Embedding] Service ready (impl={})", embeddingModel.getClass().getSimpleName());
    }

    /**
     * 生成文本向量
     * @param text 输入文本
     * @return float[] 向量数组
     */
    public float[] embed(String text) {
        log.info("[Embedding] embed: textLength={}", text != null ? text.length() : "null");
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("[Embedding] 输入文本为空");
        }
        float[] result = embeddingModel.embed(text);
        if (result == null) {
            throw new RuntimeException("[Embedding] 嵌入失败");
        }
        log.info("[Embedding] 向量生成成功: dimensions={}", result.length);
        return result;
    }

    /**
     * 批量生成向量
     */
    public List<float[]> embed(List<String> texts) {
        return embeddingModel.embed(texts);
    }

    /**
     * 获取向量维度
     */
    public int dimensions() {
        return embeddingModel.dimensions();
    }
}
