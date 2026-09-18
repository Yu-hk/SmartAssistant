-- Foundation only. No user is paused or deleted by this additive migration.
-- Apply before upgrading Consumer. Do not enable erasure until all stores are covered.
CREATE TABLE IF NOT EXISTS public.profile_lifecycle (
    user_id bigint PRIMARY KEY REFERENCES public.users(id) ON DELETE CASCADE,
    generation bigint NOT NULL DEFAULT 0 CHECK (generation >= 0),
    analysis_enabled boolean NOT NULL DEFAULT true,
    updated_at timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE public.user_profile_snapshot
    ADD COLUMN IF NOT EXISTS generation bigint NOT NULL DEFAULT 0 CHECK (generation >= 0);

-- Retain lifecycle tombstones when removing snapshots/change logs.
-- Advancing generation must lock/update this same row in the erasure transaction.
