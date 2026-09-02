-- Versioned e-commerce user profile snapshots and immutable update audit events.

CREATE TABLE IF NOT EXISTS public.user_profile_snapshot (
    user_id bigint PRIMARY KEY REFERENCES public.users(id) ON DELETE CASCADE,
    profile_version bigint NOT NULL DEFAULT 1 CHECK (profile_version > 0),
    schema_version varchar(64) NOT NULL,
    report jsonb NOT NULL,
    reliable boolean NOT NULL DEFAULT false,
    purchase_stage varchar(32),
    purchase_intent_score smallint CHECK (
        purchase_intent_score IS NULL OR purchase_intent_score BETWEEN 0 AND 100
    ),
    churn_risk varchar(16),
    source_max_message_id bigint,
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_profile_snapshot_stage
    ON public.user_profile_snapshot (purchase_stage, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_profile_snapshot_churn
    ON public.user_profile_snapshot (churn_risk, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_profile_snapshot_report_gin
    ON public.user_profile_snapshot USING gin (report);

CREATE TABLE IF NOT EXISTS public.user_profile_change_log (
    event_id varchar(36) PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    request_id varchar(128),
    base_version bigint NOT NULL CHECK (base_version >= 0),
    new_version bigint NOT NULL CHECK (new_version > 0),
    action varchar(16) NOT NULL CHECK (action IN ('CREATE', 'UPDATE')),
    changed_fields jsonb NOT NULL DEFAULT '[]'::jsonb,
    removed_fields jsonb NOT NULL DEFAULT '[]'::jsonb,
    reason text,
    evidence_refs jsonb NOT NULL DEFAULT '[]'::jsonb,
    model_name varchar(128),
    prompt_version varchar(32) NOT NULL,
    before_hash varchar(64),
    after_hash varchar(64) NOT NULL,
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_profile_change_log_user
    ON public.user_profile_change_log (user_id, new_version DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_profile_change_log_request
    ON public.user_profile_change_log (user_id, request_id)
    WHERE request_id IS NOT NULL;
