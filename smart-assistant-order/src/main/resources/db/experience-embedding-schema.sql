-- ============================================================
-- SmartAssistant Experience Embedding Schema
-- 经验向量表 — 存储 BGE embedding，支持 pgvector HNSW 搜索
-- 执行方式: psql -U postgres -d a2a_system -f experience-embedding-schema.sql
-- ============================================================

-- 1. 经验向量表
CREATE TABLE IF NOT EXISTS experience_embeddings (
    exp_id     VARCHAR(64) PRIMARY KEY,
    agent_name VARCHAR(100) NOT NULL DEFAULT '',
    intent_tag TEXT DEFAULT '',
    embedding  vector(1024),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE experience_embeddings IS '经验 BGE 向量表 — 为 COMMON/TOOL/REACT 经验存储嵌入向量，通过 pgvector 算子实现语义相似度搜索';
COMMENT ON COLUMN experience_embeddings.exp_id IS '对应 ExperienceModel.id';
COMMENT ON COLUMN experience_embeddings.agent_name IS '目标 Agent 名称，预筛选用';
COMMENT ON COLUMN experience_embeddings.intent_tag IS '意图标签，诊断用';
COMMENT ON COLUMN experience_embeddings.embedding IS 'BGE-large 1024d 归一化向量';

-- 2. HNSW 索引（O(log n) 近似搜索，比全表扫描快 100-1000 倍）
-- pgvector 0.6+ 支持 HNSW，0.5 版本请用 ivfflat
CREATE INDEX IF NOT EXISTS idx_exp_emb_hnsw
    ON experience_embeddings USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
