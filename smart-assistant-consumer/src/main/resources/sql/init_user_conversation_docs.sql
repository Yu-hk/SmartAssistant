-- 用户对话文档表：将有价值的对话沉淀为用户个人文档
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS user_conversation_docs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,           -- 对话原文（一问一答）
    agent_name VARCHAR(32),          -- 路由到的 Agent
    intent_tag VARCHAR(32),          -- 意图标签
    turn_count INTEGER DEFAULT 0,    -- 会话轮数
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    embedding vector(1024)           -- text-embedding-v4 1024维向量
);

-- 用户级别的向量索引
CREATE INDEX IF NOT EXISTS idx_user_conversation_user ON user_conversation_docs (user_id);
CREATE INDEX IF NOT EXISTS idx_user_conversation_embedding ON user_conversation_docs
    USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 200);
