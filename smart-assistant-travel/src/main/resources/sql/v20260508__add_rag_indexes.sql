-- ⭐ RAG 索引增强
-- 为向量检索、全文检索、JOIN 操作添加性能索引
-- 执行前确认 pgvector 扩展已启用：CREATE EXTENSION IF NOT EXISTS vector;
--
-- 执行方式：
--   $env:PGPASSWORD='postgres123'; & "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h 127.0.0.1 -U postgres -d a2a_system -f "smart-assistant-travel/src/main/resources/sql/v20260508__add_rag_indexes.sql"

-- ==================== Travel 模块 ====================

-- 1. pgvector HNSW 索引（travel_note_chunks.embedding）
--    HNSW 比 IVFFlat 精度更高、构建更快，适合中等数据量
--    距离函数：vector_cosine_ops（余弦相似度，与 <=> 匹配）
--    注意：向量维度必须与表定义一致（1024 维）
CREATE INDEX IF NOT EXISTS idx_chunks_embedding_hnsw
ON travel_note_chunks
USING hnsw (embedding vector_cosine_ops);

-- 2. 全文检索支持（新增 tsvector 生成列 + GIN 索引）
--    用于 PostgreSQL 原生全文搜索，替代 LIKE CONCAT('%', keyword, '%')
ALTER TABLE travel_note_chunks
ADD COLUMN IF NOT EXISTS chunk_tsvector tsvector
GENERATED ALWAYS AS (to_tsvector('simple', coalesce(chunk_text, ''))) STORED;

CREATE INDEX IF NOT EXISTS idx_chunks_tsvector_gin
ON travel_note_chunks
USING gin (chunk_tsvector);

-- 3. note_id 外键索引（加速 JOIN 到 user_travel_notes）
CREATE INDEX IF NOT EXISTS idx_chunks_note_id
ON travel_note_chunks (note_id);

-- ==================== Food 模块 ====================

-- 4. pgvector HNSW 索引（restaurant_reviews_vector.embedding）
--    向量维度：1024 维（bge-m3 模型）
CREATE INDEX IF NOT EXISTS idx_reviews_embedding_hnsw
ON restaurant_reviews_vector
USING hnsw (embedding vector_cosine_ops);

-- 5. 全文检索索引（restaurant_reviews_vector.review_text）
--    支持按评论内容关键词精确搜索
CREATE INDEX IF NOT EXISTS idx_reviews_tsvector_gin
ON restaurant_reviews_vector
USING gin (to_tsvector('simple', coalesce(review_text, '')));

-- 6. 复合索引：城市 + 菜系（加速多维过滤查询）
CREATE INDEX IF NOT EXISTS idx_reviews_city_cuisine
ON restaurant_reviews_vector (city, cuisine_type);

-- ==================== Consumer 模块 ====================
-- 如果 Consumer 有向量表，在这里添加

-- ==================== 统计数据（可选） ====================
-- 更新查询计划器的统计信息，帮助优化器选择索引
ANALYZE travel_note_chunks;
ANALYZE restaurant_reviews_vector;
