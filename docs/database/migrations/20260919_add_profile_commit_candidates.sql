-- Apply before Consumer v2 reference producer/reader. No existing profile is changed.
CREATE TABLE IF NOT EXISTS public.profile_commit_candidate (
    candidate_id varchar(36) PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    request_id varchar(128) NOT NULL,
    generation bigint NOT NULL CHECK (generation >= 0),
    payload jsonb,
    created_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamp without time zone NOT NULL,
    completed_at timestamp without time zone,
    UNIQUE (user_id, request_id, generation)
);
CREATE INDEX IF NOT EXISTS idx_profile_commit_candidate_expiry ON public.profile_commit_candidate (expires_at);
