-- Additive only. Legacy Redis hashes are neither read, migrated nor deleted here.
CREATE TABLE IF NOT EXISTS public.user_profile_entity_fact (
    user_id BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    generation BIGINT NOT NULL CHECK (generation >= 0),
    category VARCHAR(32) NOT NULL CHECK (category IN ('name','location','preference','fear','hobby')),
    fact_value VARCHAR(500) NOT NULL CHECK (length(trim(fact_value)) > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP + INTERVAL '90 days',
    PRIMARY KEY (user_id, generation, category)
);
CREATE INDEX IF NOT EXISTS idx_profile_entity_fact_expiry ON public.user_profile_entity_fact(expires_at);
