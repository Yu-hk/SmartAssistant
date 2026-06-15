/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.vectorstore;

/**
 * 向量搜索请求，替代 Spring AI 1.x 的 {@code org.springframework.ai.vectorstore.SearchRequest}。
 * <p>支持文本查询、Top-K 数量、相似度阈值以及元数据过滤表达式。</p>
 */
public class SearchRequest {

    private final String query;
    private final int topK;
    private final double similarityThreshold;
    private final String filterExpression;

    private SearchRequest(Builder builder) {
        this.query = builder.query;
        this.topK = builder.topK;
        this.similarityThreshold = builder.similarityThreshold;
        this.filterExpression = builder.filterExpression;
    }

    public String getQuery() {
        return query;
    }

    public int getTopK() {
        return topK;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public String getFilterExpression() {
        return filterExpression;
    }

    // ---- Builder ----

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String query;
        private int topK = 5;
        private double similarityThreshold = 0.0;
        private String filterExpression = "";

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }

        public Builder similarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
            return this;
        }

        public Builder filterExpression(String filterExpression) {
            this.filterExpression = filterExpression;
            return this;
        }

        public SearchRequest build() {
            if (query == null) {
                throw new IllegalArgumentException("query must not be null");
            }
            return new SearchRequest(this);
        }
    }
}
