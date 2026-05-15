/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.service.rag;

import com.example.smartassistant.common.embedding.BgeEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 向量嵌入服务（基于 BGE-large-zh 本地 ONNX 模型）
 * 
 * <p>使用 ONNX Runtime 加载 BGE-large-zh ONNX 模型，纯本地推理无需 API 调用。</p>
 */
@Service
@Slf4j
public class EmbeddingService {

    private final BgeEmbeddingModel bgeModel;

    public EmbeddingService(BgeEmbeddingModel bgeModel) {
        this.bgeModel = bgeModel;
        log.info("[Embedding] BGE embedding service ready (dim={})", bgeModel.dimensions());
    }

    /**
     * 生成文本向量
     * @param text 输入文本
     * @return float[] 向量数组（1024维）
     */
    public float[] embed(String text) {
        log.info("[Embedding] embed: textLength={}", text != null ? text.length() : "null");
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("[Embedding] 输入文本为空");
        }
        float[] result = bgeModel.embedding(text);
        if (result == null) {
            throw new RuntimeException("[Embedding] BGE embedding 失败");
        }
        log.info("[Embedding] 向量生成成功: dimensions={}", result.length);
        return result;
    }

    /**
     * 批量生成向量
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
        return bgeModel.dimensions();
    }
}
