-- ============================================================
-- 对话反馈表
-- 用于收集用户对 Agent 回复的满意度评分
-- 替代已废弃的 chat_messages 表
-- ============================================================

CREATE TABLE IF NOT EXISTS conversation_feedback (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64),
    agent_name VARCHAR(32),          -- 处理的 Agent（travel_chat / food_chat / general_chat）
    rating INTEGER CHECK (rating BETWEEN 1 AND 5),  -- 1-5 星评分
    feedback_text TEXT,               -- 可选：用户反馈文本
    metadata JSONB,                   -- 扩展元数据
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 分析索引
CREATE INDEX idx_feedback_user ON conversation_feedback (user_id);
CREATE INDEX idx_feedback_agent ON conversation_feedback (agent_name);
CREATE INDEX idx_feedback_rating ON conversation_feedback (rating);
CREATE INDEX idx_feedback_created ON conversation_feedback (created_at);
