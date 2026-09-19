-- Retain immutable receipts when erasing content, otherwise old request IDs could rebase.
CREATE TABLE IF NOT EXISTS public.profile_request_admission (
    user_id BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    request_hash CHAR(64) NOT NULL,
    input_hash CHAR(64) NOT NULL,
    generation BIGINT NOT NULL CHECK (generation >= 0),
    admitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, request_hash)
);
