-- Additive, idempotent ownership migration for per-user session isolation.
-- Legacy rows intentionally remain NULL and are visible only to administrators.
ALTER TABLE routing_call_log
    ADD COLUMN IF NOT EXISTS user_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_routing_call_log_user_created
    ON routing_call_log(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_routing_call_log_user_session
    ON routing_call_log(user_id, session_id);

COMMENT ON COLUMN routing_call_log.user_id IS
    'Authenticated owner user ID; NULL denotes legacy/system rows';
