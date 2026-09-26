-- Each conversation is independent. Keep the per-user/session primary key and
-- lifecycle status, but remove the account-wide active-session constraint.
DROP INDEX IF EXISTS public.uk_conversation_one_active_per_user;

-- Older versions suspended conversations solely because another conversation
-- belonged to the same account. They can now be continued independently.
UPDATE public.conversation_session_state
SET status = 'ACTIVE_IDLE', updated_at = CURRENT_TIMESTAMP
WHERE status IN ('SUSPENDED', 'FROZEN');

DROP INDEX IF EXISTS public.idx_conversation_suspended_fifo;

CREATE INDEX IF NOT EXISTS idx_conversation_user_status
    ON public.conversation_session_state (user_id, status, updated_at);
