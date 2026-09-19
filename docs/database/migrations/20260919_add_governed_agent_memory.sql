CREATE TABLE IF NOT EXISTS public.profile_agent_memory (
    user_id BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    agent VARCHAR(16) NOT NULL CHECK (agent IN ('order','product','general')),
    memory_key VARCHAR(64) NOT NULL CHECK (memory_key ~ '^[a-zA-Z][a-zA-Z0-9_]{0,63}$'),
    memory_value VARCHAR(500) NOT NULL CHECK (length(trim(memory_value)) > 0),
    generation BIGINT NOT NULL CHECK (generation >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '90 days'),
    PRIMARY KEY (user_id,agent,memory_key)
);
CREATE INDEX IF NOT EXISTS idx_profile_agent_memory_expiry ON public.profile_agent_memory(expires_at);
