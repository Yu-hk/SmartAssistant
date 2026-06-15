/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.common.document;

import java.util.Collections;
import java.util.Map;

/**
 * 文档对象，替代 Spring AI 1.x 的 {@code org.springframework.ai.document.Document}。
 * <p>用于向量化存储和语义检索，包含文本内容、元数据以及相关性得分。</p>
 */
public class Document {

    private final String id;
    private final String text;
    private final Map<String, Object> metadata;
    private Double score;

    /**
     * 创建一个文档。
     *
     * @param id       文档唯一标识
     * @param text     文档文本内容
     * @param metadata 文档元数据
     */
    public Document(String id, String text, Map<String, Object> metadata) {
        this.id = id;
        this.text = text;
        this.metadata = metadata != null ? metadata : Collections.emptyMap();
    }

    /** 创建一个无元数据的文档。 */
    public Document(String id, String text) {
        this(id, text, Collections.emptyMap());
    }

    /** 创建一个无 ID、无元数据的文档。 */
    public Document(String text) {
        this(java.util.UUID.randomUUID().toString(), text, Collections.emptyMap());
    }

    public String getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Document document)) return false;
        return id.equals(document.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Document{id='" + id + "', text='" + (text != null ? text.substring(0, Math.min(text.length(), 50)) : "") + "...'}";
    }

    // ---- Builder ----

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String text;
        private Map<String, Object> metadata;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Document build() {
            return new Document(id, text, metadata);
        }
    }
}
