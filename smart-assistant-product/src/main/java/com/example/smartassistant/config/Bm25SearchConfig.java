/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 */
package com.example.smartassistant.config;

import com.example.smartassistant.common.rag.Bm25Scorer;
import com.example.smartassistant.common.tokenizer.ChineseTokenizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies the common BM25 scorer required by the RAG search pipeline. */
@Configuration
public class Bm25SearchConfig {

    @Bean("commonBm25Scorer")
    public Bm25Scorer commonBm25Scorer(ChineseTokenizer tokenizer) {
        return new Bm25Scorer(tokenizer);
    }
}
