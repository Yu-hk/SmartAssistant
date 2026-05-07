-- SmartAssistant 数据库初始化脚本
-- 需要先执行:
-- CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- 启用 pg_stat_statements（SQL 监控功能需要）
-- 1. 修改 postgresql.conf: shared_preload_libraries = 'pg_stat_statements'
-- 2. 重启 PostgreSQL 服务
-- 3. 执行此 SQL
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
