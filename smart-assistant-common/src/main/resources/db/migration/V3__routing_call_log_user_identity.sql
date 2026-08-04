-- Associate every new routing log with the authenticated user that owns it.
-- Older synchronous writes accidentally stored userId in session_id. Recover
-- those rows when the value is safely recognizable; other legacy rows remain
-- NULL and are visible only to administrator queries.
ALTER TABLE routing_call_log
    ADD COLUMN IF NOT EXISTS user_id BIGINT;

UPDATE routing_call_log
SET user_id = CAST(session_id AS BIGINT)
WHERE user_id IS NULL
  AND session_id ~ '^[1-9][0-9]*$'
  AND LENGTH(session_id) <= 18;

CREATE INDEX IF NOT EXISTS idx_routing_call_log_user_created
    ON routing_call_log (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_routing_call_log_user_session
    ON routing_call_log (user_id, session_id);

-- The insight ticket table is created lazily by the consumer. Upgrade it when
-- it already exists; the service also applies the same idempotent change.
ALTER TABLE IF EXISTS insight_ticket
    ADD COLUMN IF NOT EXISTS user_id BIGINT;
