-- ============================================================
-- Migration: V20260423__add_role_to_users.sql
-- 描述: 为 users 表添加角色字段，支持基于角色的权限控制
-- 角色值:
--   ROLE_USER  - 普通用户（默认）：只能进行一般性问答
--   ROLE_ADMIN - 管理员：可以使用数据统计/查询、缓存统计等高权限功能
-- ============================================================

-- 1. 添加 role 列（默认 ROLE_USER，兼容已有用户数据）
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS role VARCHAR(32) NOT NULL DEFAULT 'ROLE_USER';

-- 2. 为 role 列添加索引（按角色查询用户时使用）
CREATE INDEX IF NOT EXISTS idx_users_role ON users(role);

-- 3. 将已有用户（role 为 NULL 的历史数据）统一设为 ROLE_USER
UPDATE users SET role = 'ROLE_USER' WHERE role IS NULL OR role = '';

-- 4. 手动将指定账号提升为管理员（按需修改 username）
-- UPDATE users SET role = 'ROLE_ADMIN' WHERE username = 'admin';

-- 验证结果
-- SELECT id, username, role FROM users ORDER BY id;
