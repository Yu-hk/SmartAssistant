-- ============================================================
-- 数据库清理脚本 v2026-05-08
-- 功能：删除已迁出到文件存储的废弃表
-- 说明：
--   user_profiles             → 已迁至 data/users/{userId}/preferences.json
--   user_preference_vectors   → 已迁至 data/users/{userId}/preferences.json
--   user_conversation_docs    → 已迁至 data/users/{userId}/memories/*.md
-- ============================================================

-- 删除用户偏好向量表（已迁至文件存储）
DROP TABLE IF EXISTS public.user_preference_vectors CASCADE;

-- 删除用户画像表（已迁至文件存储）
DROP TABLE IF EXISTS public.user_profiles CASCADE;

-- 删除用户对话文档表（如需清理已建表，取消注释）
-- DROP TABLE IF EXISTS public.user_conversation_docs CASCADE;
