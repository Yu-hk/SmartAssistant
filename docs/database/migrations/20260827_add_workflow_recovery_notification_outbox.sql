-- Transactional-outbox state for at-least-once user notification delivery.
ALTER TABLE public.workflow_recovery_jobs
    ADD COLUMN IF NOT EXISTS notification_pending boolean NOT NULL DEFAULT false;

ALTER TABLE public.workflow_recovery_jobs
    ADD COLUMN IF NOT EXISTS notification_published_at timestamp without time zone;

CREATE INDEX IF NOT EXISTS idx_workflow_recovery_notification_outbox
    ON public.workflow_recovery_jobs (notification_pending, updated_at)
    WHERE notification_pending = true;
