-- ⭐ 为 travel_note_chunks 表添加 content_type 列
-- 在分块时自动标记内容类型：scenic / food / accommodation / transport / general
-- 执行后需要运行 rebuildAllChunks() 重新分块以为存量数据打标签

ALTER TABLE travel_note_chunks 
ADD COLUMN IF NOT EXISTS content_type VARCHAR(20) NOT NULL DEFAULT 'general';

-- 添加索引以加速按类型过滤的查询
CREATE INDEX IF NOT EXISTS idx_chunks_content_type 
ON travel_note_chunks (content_type);

CREATE INDEX IF NOT EXISTS idx_chunks_location_type 
ON travel_note_chunks (location_keywords, content_type);
