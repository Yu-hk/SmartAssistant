-- Durable audit and status store for asynchronous LangGraph workflow recovery requests.

CREATE TABLE IF NOT EXISTS public.workflow_recovery_jobs (
    recovery_id varchar(64) PRIMARY KEY,
    request_id varchar(128) NOT NULL,
    checkpoint_updated_at_epoch_ms bigint NOT NULL,
    trigger varchar(24) NOT NULL CHECK (trigger IN (
        'AUTO_STALE', 'USER_MANUAL', 'ADMIN_MANUAL', 'STARTUP_REPAIR'
    )),
    workflow_owner_id bigint NOT NULL,
    requested_by bigint,
    reason varchar(512),
    status varchar(32) NOT NULL CHECK (status IN (
        'REQUESTED', 'QUEUED', 'RECOVERING', 'RETRY_SCHEDULED', 'SUCCEEDED',
        'DEAD_LETTERED', 'SKIPPED_ACTIVE', 'SKIPPED_APPROVAL',
        'SKIPPED_SUPERSEDED', 'SKIPPED_DUPLICATE', 'REJECTED_INVALID_COMMAND'
    )),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error text,
    result text,
    notification_pending boolean NOT NULL DEFAULT false,
    notification_published_at timestamp without time zone,
    requested_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_workflow_recovery_jobs_request
    ON public.workflow_recovery_jobs (request_id, requested_at DESC);

CREATE INDEX IF NOT EXISTS idx_workflow_recovery_jobs_status
    ON public.workflow_recovery_jobs (status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_workflow_recovery_notification_outbox
    ON public.workflow_recovery_jobs (notification_pending, updated_at)
    WHERE notification_pending = true;
