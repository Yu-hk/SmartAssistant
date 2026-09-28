-- Additive, immutable record of administrator-initiated portrait cleanup.
-- Keep actor/target IDs as values so account removal cannot cascade into audit loss.
CREATE TABLE IF NOT EXISTS public.profile_admin_cleanup_audit (
    action_id uuid PRIMARY KEY,
    actor_user_id bigint NOT NULL,
    target_user_id bigint NOT NULL,
    reason_code varchar(32) NOT NULL CHECK (reason_code IN
        ('USER_REQUEST','SECURITY_INCIDENT','DATA_CORRECTION','OTHER')),
    job_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_profile_admin_cleanup_audit_target
    ON public.profile_admin_cleanup_audit(target_user_id,created_at DESC);
