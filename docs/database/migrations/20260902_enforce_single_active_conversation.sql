-- One interactive conversation per user. Redis provides the fast lease; this partial
-- unique index is the durable split-brain backstop for PostgreSQL.

ALTER TABLE public.conversation_session_state
    ALTER COLUMN status SET DEFAULT 'ACTIVE_IDLE';

-- Preserve the most recently touched active session and suspend older duplicates before
-- adding the uniqueness constraint. The migration is safe to run more than once.
UPDATE public.conversation_session_state
SET status = 'SUSPENDED', updated_at = CURRENT_TIMESTAMP
WHERE status = 'FROZEN';

WITH ranked AS (
    SELECT user_id,
           session_id,
           row_number() OVER (
               PARTITION BY user_id
               ORDER BY updated_at DESC, session_id DESC
           ) AS position
    FROM public.conversation_session_state
    WHERE status IN ('ACTIVE', 'ACTIVE_IDLE', 'ACTIVE_RUNNING')
)
UPDATE public.conversation_session_state state
SET status = CASE WHEN ranked.position = 1 THEN 'ACTIVE_IDLE' ELSE 'SUSPENDED' END,
    updated_at = CURRENT_TIMESTAMP
FROM ranked
WHERE state.user_id = ranked.user_id
  AND state.session_id = ranked.session_id;

CREATE UNIQUE INDEX IF NOT EXISTS uk_conversation_one_active_per_user
    ON public.conversation_session_state (user_id)
    WHERE status IN ('ACTIVE_IDLE', 'ACTIVE_RUNNING');

DROP INDEX IF EXISTS public.idx_conversation_frozen_fifo;

CREATE INDEX IF NOT EXISTS idx_conversation_suspended_fifo
    ON public.conversation_session_state (user_id, updated_at)
    WHERE status = 'SUSPENDED';
