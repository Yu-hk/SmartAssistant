package com.example.smartassistant.router.service.experience;

import java.time.LocalDateTime;

/**
 * 经验向量实体 — 映射 experience_embeddings 表。
 * 用 pgvector 的 vector(1024) 列存储 BGE 嵌入向量，
 * HNSW 索引支持 O(log n) 余弦相似度搜索。
 */
public class ExperienceEmbeddingEntity {
    private String expId;
    private String agentName;
    private String intentTag;
    private String embedding;     // pgvector 存储为字符串，格式 "[0.1,0.2,...]"
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getExpId() { return expId; }
    public void setExpId(String expId) { this.expId = expId; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getIntentTag() { return intentTag; }
    public void setIntentTag(String intentTag) { this.intentTag = intentTag; }

    public String getEmbedding() { return embedding; }
    public void setEmbedding(String embedding) { this.embedding = embedding; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
